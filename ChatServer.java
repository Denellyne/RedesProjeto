import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

/*      ->  (Depois eliminar este bloco!)   <-

 *   • NIO não bloqueante com Selector (evita bloqueio em leituras/escritas)
 *   • Máquina de estados: init → outside → inside
 *   • Comandos: /nick, /join, /leave, /bye, /priv (opcional +10%)
 *   • Mensagens simples com escape de '/' inicial (//texto → ///texto no cliente)
 *   • Difusão por sala: JOINED, LEFT, MESSAGE, NEWNICK
 *   • Mensagens privadas (/priv) – funciona mesmo fora de sala
 *   • Buffering por cliente: lida com mensagens fragmentadas (testes com ncat)
 *   • OP_WRITE ativado APENAS quando há dados para enviar (eficiência)
 *   • NENHUMA saída no stdout (apenas erros fatais em stderr)
 *   • Formato EXATO das respostas (OK, ERROR, etc.) – compatível com testes automáticos

 * Estrutura do código:
 *   1. Configurações NIO (buffers, charset)
 *   2. Classe ClientState (estado por cliente)
 *   3. Estruturas globais (nicks, salas, clientes)
 *   4. main() – loop do Selector
 *   5. Aceitação de conexões
 *   6. Leitura (OP_READ) + extração de linhas
 *   7. Processamento de comandos (máquina de estados)
 *   8. Handlers para cada comando
 *   9. Utilitários (broadcast, enqueue, close)
 *  10. Escrita (OP_WRITE) com suporte a escrita parcial
 */


public class ChatServer {

    // Charset UTF-8 para codificação/decodificação de texto
    private static final Charset CHARSET = Charset.forName("UTF-8");
    private static final CharsetDecoder DECODER = CHARSET.newDecoder();
    private static final CharsetEncoder ENCODER = CHARSET.newEncoder();

    // Buffer reutilizável para leitura (16KB – suficiente para mensagens normais)
    private static final ByteBuffer READ_BUFFER = ByteBuffer.allocate(16384);


    // =============================================================
    //  ESTADO POR CLIENTE
    // =============================================================

    private static class ClientState {

        String nick = null;     // Nick do utilizador (null = ainda nao definido)
        String room = null;     // Sala atual (null = fora de sala)
        String state = "init";  // Estado da máquina de estados: "init", "outside", "inside"

        // Buffer de entrada: acumula bytes até encontrar \n
        final StringBuilder inputBuffer = new StringBuilder();
        
        // Fila de saída: mensagens a enviar para este cliente
        final Deque<String> outputQueue = new LinkedList<>();
    }


    // =============================================================
    //  ESTRUTURAS GLOBAIS
    // =============================================================

    // Conjunto de nicks em uso (evita duplicados)
    private static final Set<String> USED_NICKS = new HashSet<>();
    
    // Mapa: nome da sala → conjunto de SelectionKey dos clientes nessa sala
    private static final Map<String, Set<SelectionKey>> ROOMS = new HashMap<>();
    
    // Conjunto de todas as SelectionKey ativas (para /priv e limpeza)
    private static final Set<SelectionKey> ALL_CLIENTS = new HashSet<>();

    // =============================================================
    //  MÉTODO PRINCIPAL – LOOP DO SELECTOR
    // =============================================================
    
    public static void main(String[] args) {
        // Validação da linha de comando
        if (args.length != 1) {
            System.err.println("Uso: java ChatServer <porta>");
            System.exit(1);
        }

        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Porta inválida: " + args[0]);
            System.exit(1);
            return;
        }

        try {
            // Criação do canal do servidor (não bloqueante)
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(port));

            // Selector: gerencia múltiplos canais de forma eficiente
            Selector selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            // Loop infinito do servidor
            while (true) {
                try {
                    // select() bloqueia até haver atividade (aceitação, leitura, escrita)
                    if (selector.select() == 0) continue;
                } catch (IOException e) {
                    System.err.println("Erro no selector: " + e.getMessage());
                    continue;
                }

                // Processa todas as keys com atividade
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove(); // Remove da lista de selecionadas

                    if (!key.isValid()) continue; // Ignora keys inválidas

                    try {
                        if (key.isAcceptable()) {
                            // Nova conexão entrante
                            acceptConnection((ServerSocketChannel) key.channel(), selector);
                        } else if (key.isReadable()) {
                            // Dados para ler do cliente
                            readFromClient(key);
                        } else if (key.isWritable()) {
                            // Socket pronto para escrita (há dados na outputQueue)
                            writeToClient(key);
                        }
                    } catch (Exception e) {
                        // Qualquer exceção → fecha o cliente
                        closeClient(key, "Exceção: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            // Erro fatal no arranque do servidor
            System.err.println("Erro fatal no servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =============================================================
    //  ACEITAÇÃO DE NOVA CONEXÃO (OP_ACCEPT)
    // =============================================================
    /**
     * Aceita uma nova conexão TCP.
     * Regista o canal com OP_READ e anexa um ClientState vazio.
     */
    private static void acceptConnection(ServerSocketChannel serverChannel, Selector selector) throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) return; // wakeup espúrio

        // Configura como não bloqueante
        clientChannel.configureBlocking(false);
        
        // Cria estado inicial do cliente
        ClientState state = new ClientState();
        
        // Regista no selector apenas com OP_READ (OP_WRITE só quando necessário)
        SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ, state);
        
        // Adiciona ao conjunto global de clientes
        ALL_CLIENTS.add(key);
    }

    // =============================================================
    //  LEITURA DE DADOS (OP_READ)
    // =============================================================
    /**
     * Lê dados do socket, decodifica para texto e acumula no inputBuffer.
     * Extrai linhas completas terminadas em \n e processa uma a uma.
     */
    private static void readFromClient(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState cs = (ClientState) key.attachment();

        // Limpa buffer para nova leitura
        READ_BUFFER.clear();
        int bytesRead = channel.read(READ_BUFFER);
        
        // -1 = EOF → cliente fechou a conexão
        if (bytesRead == -1) {
            closeClient(key, "Conexão fechada pelo cliente");
            return;
        }
        if (bytesRead == 0) return; // Nada lido (raro)

        // Prepara buffer para decodificação
        READ_BUFFER.flip();
        cs.inputBuffer.append(DECODER.decode(READ_BUFFER));

        // Extrai e processa todas as linhas completas
        String line;
        while ((line = extractLine(cs.inputBuffer)) != null) {
            processLine(key, line.trim());
        }
    }

    // =============================================================
    //  PROCESSAMENTO DE LINHA (PROTOCOLO + MÁQUINA DE ESTADOS)
    // =============================================================
    /**
     * Processa uma linha completa recebida do cliente.
     * - Remove escape de '/' inicial (///texto → //texto)
     * - Se não começa com '/', é mensagem simples (só em 'inside')
     * - Se começa com '/', é comando → divide e despacha
     */
    private static void processLine(SelectionKey key, String line) {
        ClientState cs = (ClientState) key.attachment();

        if (line.isEmpty()) return; // Ignora linhas vazias

        // === ESCAPE DE '/' INICIAL ===
        // Cliente envia ///texto para mostrar //texto
        if (line.startsWith("//")) {
            line = line.substring(1); // Remove um '/', deixa //
        }

        // === MENSAGEM SIMPLES (não começa com '/') ===
        if (!line.startsWith("/")) {
            if ("inside".equals(cs.state)) {
                // Difunde para toda a sala
                broadcastToRoom(cs.room, "MESSAGE " + cs.nick + " " + line);
            } else {
                // Fora de sala → ERROR
                send(key, "ERROR");
            }
            return;
        }

        // === COMANDO (começa com '/') ===
        String[] parts = line.substring(1).split(" ", 2); // Remove '/' inicial
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "nick":
                handleNick(key, args.trim());
                break;
            case "join":
                handleJoin(key, args.trim());
                break;
            case "leave":
                handleLeave(key);
                break;
            case "bye":
                handleBye(key);
                break;
            case "priv":
                handlePriv(key, args);
                break;
            default:
                // Para comandos desconhecidos
                send(key, "ERROR");
        }
    }

    // =============================================================
    //  HANDLERS DOS COMANDOS
    // =============================================================

    /** /nick <novo_nick> */
    private static void handleNick(SelectionKey key, String newNick) {
        if (newNick.isEmpty()) {
            send(key, "ERROR");
            return;
        }

        ClientState cs = (ClientState) key.attachment();

        // Verifica se nick já existe
        if (cs.nick != null && USED_NICKS.contains(newNick)) {
            send(key, "ERROR");
            return;
        }

        String oldNick = cs.nick;

        // Remove nick antigo (se existir)
        if (oldNick != null) {
            USED_NICKS.remove(oldNick);
            if ("inside".equals(cs.state)) {
                broadcastToRoom(cs.room, "NEWNICK " + oldNick + " " + newNick);
            }
        } else {
            // Primeiro nick → sai de 'init'
            cs.state = "outside";
        }

        // Atualiza nick
        cs.nick = newNick;
        USED_NICKS.add(newNick);
        send(key, "OK");
    }

    /** /join <sala> */
    private static void handleJoin(SelectionKey key, String roomName) {
        ClientState cs = (ClientState) key.attachment();

        // Validações
        if (roomName.isEmpty() || "init".equals(cs.state)) {
            send(key, "ERROR");
            return;
        }

        String oldRoom = cs.room;

        // Sai da sala antiga (se estiver numa)
        if (oldRoom != null) {
            leaveRoom(key, oldRoom);
        }

        // Entra na nova sala
        cs.room = roomName;
        cs.state = "inside";

        // Cria sala se não existir
        ROOMS.computeIfAbsent(roomName, k -> new HashSet<>()).add(key);
        send(key, "OK");
        broadcastToRoom(roomName, "JOINED " + cs.nick);
    }

    /** /leave */
    private static void handleLeave(SelectionKey key) {
        ClientState cs = (ClientState) key.attachment();
        if (!"inside".equals(cs.state)) {
            send(key, "ERROR");
            return;
        }

        leaveRoom(key, cs.room);
        cs.room = null;
        cs.state = "outside";
        send(key, "OK");
    }

    /** /bye */
    private static void handleBye(SelectionKey key) {
        ClientState cs = (ClientState) key.attachment();
        if ("inside".equals(cs.state)) {
            leaveRoom(key, cs.room);
        }
        send(key, "BYE");
        closeClient(key, "Cliente saiu com /bye");
    }

    /** /priv <nick> <msg> – opcional +10% */
    private static void handlePriv(SelectionKey key, String args) {
        ClientState sender = (ClientState) key.attachment();
        
        // Deve estar em outside ou inside
        if (!"inside".equals(sender.state) && !"outside".equals(sender.state)) {
            send(key, "ERROR");
            return;
        }

        String[] parts = args.split(" ", 2);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            send(key, "ERROR");
            return;
        }

        String targetNick = parts[0];
        String message = parts[1];

        SelectionKey targetKey = findClientByNick(targetNick);
        if (targetKey == null) {
            send(key, "ERROR");
            return;
        }

        ClientState target = (ClientState) targetKey.attachment();
        String privMsg = "PRIVATE " + sender.nick + " " + message;
        enqueue(targetKey, privMsg);
        send(key, "OK");
    }

    // =============================================================
    //  UTILITÁRIOS
    // =============================================================

    /** Remove cliente da sala e notifica os outros */
    private static void leaveRoom(SelectionKey key, String room) {
        ClientState cs = (ClientState) key.attachment();
        Set<SelectionKey> roomClients = ROOMS.get(room);
        if (roomClients != null) {
            roomClients.remove(key);
            if (roomClients.isEmpty()) ROOMS.remove(room); // Remove sala vazia
            broadcastToRoom(room, "LEFT " + cs.nick);
        }
    }

    /** Envia mensagem para todos na sala */
    private static void broadcastToRoom(String room, String message) {
        Set<SelectionKey> clients = ROOMS.get(room);
        if (clients == null) return;
        for (SelectionKey k : clients) {
            if (k.isValid()) enqueue(k, message);
        }
    }

    /** Procura cliente pelo nick */
    private static SelectionKey findClientByNick(String nick) {
        for (SelectionKey k : ALL_CLIENTS) {
            if (!k.isValid()) continue;
            ClientState cs = (ClientState) k.attachment();
            if (nick.equals(cs.nick)) return k;
        }
        return null;
    }

    /** Extrai linha terminada em \n do buffer */
    private static String extractLine(StringBuilder sb) {
        int idx = sb.indexOf("\n");
        if (idx == -1) return null;
        String line = sb.substring(0, idx);
        sb.delete(0, idx + 1);
        return line;
    }

    /** Envia mensagem para um cliente específico */
    private static void send(SelectionKey key, String message) {
        enqueue(key, message);
    }

    /** Coloca mensagem na fila de saída e ativa OP_WRITE */
    private static void enqueue(SelectionKey key, String message) {
        ClientState cs = (ClientState) key.attachment();
        cs.outputQueue.add(message + "\n");
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    // =============================================================
    // 10. ESCRITA DE DADOS (OP_WRITE)
    // =============================================================
    /**
     * Escreve mensagens da outputQueue para o socket.
     * Lida com escrita parcial (raro, mas possível).
     */
    private static void writeToClient(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState cs = (ClientState) key.attachment();

        while (!cs.outputQueue.isEmpty()) {
            String msg = cs.outputQueue.peek();
            ByteBuffer buf = ENCODER.encode(CharBuffer.wrap(msg));

            int written = channel.write(buf);
            if (written == 0) break; // Socket cheio → espera próximo OP_WRITE

            if (buf.hasRemaining()) {
                // Escrita parcial → recria string restante
                String remaining = msg.substring(written);
                cs.outputQueue.poll();
                cs.outputQueue.addFirst(remaining);
                break;
            } else {
                // Mensagem completa enviada
                cs.outputQueue.poll();
            }
        }

        // Se fila vazia → desativa OP_WRITE
        if (cs.outputQueue.isEmpty()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    // =============================================================
    //  FECHAR CLIENTE
    // =============================================================
    /**
     * Fecha conexão, remove de todas as estruturas e notifica sala (se aplicável).
     */
    private static void closeClient(SelectionKey key, String reason) {
        ClientState cs = (ClientState) key.attachment();

        // Remove de estruturas globais
        ALL_CLIENTS.remove(key);
        if (cs.nick != null) USED_NICKS.remove(cs.nick);
        if (cs.room != null) leaveRoom(key, cs.room);

        // Cancela key e fecha canal
        key.cancel();
        try {
            key.channel().close();
        } catch (IOException ignored) {}
    }
}