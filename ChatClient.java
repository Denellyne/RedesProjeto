import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.Iterator;
import javax.swing.*;

/**
 * ChatClient.java – VERSÃO COMPLETA, CORRIGIDA E ALTAMENTE COMENTADA
 * 
 * Implementa TODAS as funcionalidades exigidas no enunciado:
 * 
 *   • Interface gráfica em Swing (EDT – Event Dispatch Thread)
 *   • Thread separada para leitura assíncrona (NIO não bloqueante)
 *   • Protocolo completo: /nick, /join, /leave, /bye, /priv
 *   • Escape de '/' inicial: digita //texto → envia ///texto
 *   • Formatação amigável na GUI (+5%):
 *        MESSAGE ana Oi → "ana: Oi"
 *        NEWNICK ana anita → "ana mudou de nome para anita"
 *        JOINED pedro → "pedro entrou na sala"
 *        LEFT pedro → "pedro saiu da sala"
 *        PRIVATE ana Olá → "[PRIVADO] ana: Olá"
 *   • Leitura não bloqueante com SocketChannel + Selector
 *   • Envia /bye ao fechar a janela
 *   • Nenhuma saída extra no console
 * 
 * Baseado no esqueleto "Grok", mas totalmente reescrito para cumprir o projeto.
 * 
 * Estrutura:
 *   1. Configurações NIO
 *   2. Variáveis da GUI (não modificar)
 *   3. Estado do cliente (nick, sala, etc.)
 *   4. Construtor + inicialização da GUI
 *   5. Thread de leitura (NIO com Selector)
 *   6. Envio de mensagens (com escape)
 *   7. Formatação amigável
 *   8. Fecho da janela → /bye
 */
public class ChatClient {

    // =============================================================
    // 1. CONFIGURAÇÕES NIO (leitura não bloqueante)
    // =============================================================
    private static final Charset CHARSET = Charset.forName("UTF-8");
    private static final CharsetDecoder DECODER = CHARSET.newDecoder();
    private static final CharsetEncoder ENCODER = CHARSET.newEncoder();
    private static final ByteBuffer READ_BUFFER = ByteBuffer.allocate(16384);

    // =============================================================
    // 2. VARIÁVEIS DA INTERFACE GRÁFICA (NÃO MODIFICAR)
    // =============================================================
    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextArea chatArea = new JTextArea();
    // =============================================================

    // =============================================================
    // 3. ESTADO DO CLIENTE (adicionado)
    // =============================================================
    private SocketChannel channel;
    private Selector readSelector;
    private volatile boolean running = true;
    private String currentNick = null;
    private String currentRoom = null;

    // Thread para leitura assíncrona
    private Thread readThread;

    // =============================================================
    // 4. CONSTRUTOR + INICIALIZAÇÃO DA GUI
    // =============================================================
    public ChatClient(String server, int port) throws IOException {
        // Conexão NIO não bloqueante
        channel = SocketChannel.open(new InetSocketAddress(server, port));
        channel.configureBlocking(false);
        readSelector = Selector.open();
        channel.register(readSelector, SelectionKey.OP_READ);

        // Inicialização da GUI (NÃO MODIFICAR)
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // Interceptar fecho
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(chatBox, BorderLayout.CENTER);
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.SOUTH);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        frame.setSize(500, 300);
        frame.setVisible(true);
        chatArea.setEditable(false);
        chatBox.setEditable(true);

        // Envio ao premir Enter
        chatBox.addActionListener(e -> {
            String text = chatBox.getText().trim();

        // comando /help (este comando nao e mandado para servidor)
            if (text.equalsIgnoreCase("/help")) {
                showHelp();
                chatBox.setText("");
                return;
            }

            if (!text.isEmpty()) {
                try {
                    sendMessage(text);
                } catch (IOException ex) {
                    appendFormatted("Erro ao enviar: " + ex.getMessage());
                }
            }
            chatBox.setText("");
        });

        // Foco inicial
        frame.addWindowListener(new WindowAdapter() {
            public void windowOpened(WindowEvent e) {
                chatBox.requestFocusInWindow();
            }

            // Fecho da janela → envia /bye
            public void windowClosing(WindowEvent e) {
                try {
                    sendMessage("/bye");
                } catch (IOException ignored) {}
                System.exit(0);
            }
        });
        // =============================================================

        // Inicia thread de leitura
        startReadThread();
    }

    // =============================================================
    // 5. THREAD DE LEITURA (NIO com Selector)
    // =============================================================
    /**
     * Thread dedicada à leitura assíncrona do servidor.
     * Usa Selector para evitar bloqueio na GUI.
     */
    private void startReadThread() {
        readThread = new Thread(() -> {
            try {
                while (running && channel.isOpen()) {
                    if (readSelector.select(100) == 0) continue;

                    Iterator<SelectionKey> it = readSelector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();

                        if (key.isReadable()) {
                            readFromServer();
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    SwingUtilities.invokeLater(() -> appendFormatted("Conexão perdida: " + e.getMessage()));
                }
            } finally {
                closeConnection();
            }
        }, "ReadThread");
        readThread.setDaemon(true);
        readThread.start();
    }

    /**
     * Lê dados do servidor, extrai linhas e processa.
     */
    private void readFromServer() throws IOException {
        READ_BUFFER.clear();
        int bytesRead = channel.read(READ_BUFFER);
        if (bytesRead == -1) {
            throw new IOException("Servidor fechou a conexão!");
        }
        if (bytesRead == 0) return;

        READ_BUFFER.flip();
        String data = DECODER.decode(READ_BUFFER).toString();

        StringBuilder buffer = new StringBuilder();
        buffer.append(data);

        String line;
        while ((line = extractLine(buffer)) != null) {
            processServerMessage(line.trim());
        }
    }

    // =============================================================
    // 6. ENVIO DE MENSAGENS (com escape de '/')
    // =============================================================
    /**
     * Envia texto para o servidor.
     * Aplica escape: //texto → ///texto
     */
    private void sendMessage(String text) throws IOException {
        if (text.startsWith("//")) {
            text = "/" + text; // //texto → ///texto
        }
        String message = text + "\n";
        ByteBuffer buf = ENCODER.encode(CharBuffer.wrap(message));
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    // =============================================================
    // 7. PROCESSAMENTO DE MENSAGENS DO SERVIDOR + FORMATAÇÃO AMIGÁVEL
    // =============================================================
    /**
     * Processa linha recebida do servidor e formata na GUI.
     */
    private void processServerMessage(String line) {
        if (line.isEmpty()) return;

        String[] parts = line.split(" ", 3);
        String type = parts[0];

        switch (type) {
            case "OK":
            case "ERROR":
            case "BYE":
                appendFormatted(type);
                break;

            case "MESSAGE":
                if (parts.length >= 3) {
                    String nick = parts[1];
                    String msg = parts[2];
                    appendFormatted("[" + nick + "]: " + msg);
                }
                break;

            case "NEWNICK":
                if (parts.length >= 3) {
                    String oldNick = parts[1];
                    String newNick = parts[2];
                    appendFormatted("[" + oldNick + "] mudou de nome para [" + newNick + "]");
                }
                break;

            case "JOINED":
                if (parts.length >= 2) {
                    appendFormatted("[" + parts[1] + "] entrou na sala");
                }
                break;

            case "LEFT":
                if (parts.length >= 2) {
                    appendFormatted("[" + parts[1] + "] saiu da sala");
                }
                break;

            case "PRIVATE":
                if (parts.length >= 3) {
                    String nick = parts[1];
                    String msg = parts[2];
                    appendFormatted("[PRIVADO] [" + nick + "]: " + msg);
                }
                break;

            default:
                appendFormatted(line); // fallback
        }
    }


    // =============================================================
    //  MOSTRAR COMANDOS
    // =============================================================
    private void showHelp() {
        String help = "Comandos disponíveis:\n" +
                    " /nick <nome> - (re)definir o teu nick\n" +
                    " /join <sala> - entrar numa sala\n" +
                    " /leave - sair da sala atual\n" +
                    " /priv <nick> <msg> - enviar mensagem privada\n" +
                    " /bye - sair do chat\n" +
                    " /help - mostrar esta ajuda";

        // Divide em linhas e adiciona uma a uma (com \n)
        for (String line : help.split("\n")) {
            appendFormatted(line);
        }
    }

    // =============================================================
    // 2. FORMATAR MENSAGEM DE ERRO DO SERVIDOR
    // =============================================================
    private void formatError() {
        appendFormatted("[ERRO] Command not found. Use /help para adquirir ajuda de comandos.");
    }

    // =============================================================
    // 8. UTILITÁRIOS
    // =============================================================

    /** Extrai linha terminada em \n */
    private String extractLine(StringBuilder sb) {
        int idx = sb.indexOf("\n");
        if (idx == -1) return null;
        String line = sb.substring(0, idx);
        sb.delete(0, idx + 1);
        return line;
    }

    /** Adiciona texto formatado na GUI (na EDT) */
    private void appendFormatted(String text) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(text + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    /** Fecha conexão e selector */
    private void closeConnection() {
        running = false;
        try { if (readSelector != null) readSelector.close(); } catch (Exception ignored) {}
        try { if (channel != null) channel.close(); } catch (Exception ignored) {}
        frame.dispose();
    }

    // =============================================================
    // 9. MÉTODO PRINCIPAL
    // =============================================================
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Uso: java ChatClient <servidor> <porta>");
            System.exit(1);
        }
        new ChatClient(args[0], Integer.parseInt(args[1]));
    }
}