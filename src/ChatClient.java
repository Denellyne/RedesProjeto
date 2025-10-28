import java.io.*;
import java.net.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public class ChatClient {

  // Variáveis relacionadas com a interface gráfica --- * NÃO MODIFICAR *
  JFrame frame = new JFrame("Chat Client");
  private JTextField chatBox = new JTextField();
  private JTextArea chatArea = new JTextArea();
  private Socket clientSocket;
  private DataOutputStream outToServer;
  private BufferedReader inFromServer;
  // --- Fim das variáveis relacionadas coma interface gráfica

  // Se for necessário adicionar variáveis ao objecto ChatClient, devem
  // ser colocadas aqui

  // Método a usar para acrescentar uma string à caixa de texto
  // * NÃO MODIFICAR *
  public void printMessage(final String message) {
    chatArea.append(message);
  }

  // Construtor
  public ChatClient(String server, int port) throws IOException {
    clientSocket = new Socket(server, port);
    outToServer = new DataOutputStream(clientSocket.getOutputStream());
    inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

    // Inicialização da interface gráfica --- * NÃO MODIFICAR *
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    JPanel panel = new JPanel();
    panel.setLayout(new BorderLayout());
    panel.add(chatBox);
    frame.setLayout(new BorderLayout());
    frame.add(panel, BorderLayout.SOUTH);
    frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
    frame.setSize(500, 300);
    frame.setVisible(true);
    chatArea.setEditable(false);
    chatBox.setEditable(true);
    chatBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          newMessage(chatBox.getText());
        } catch (IOException ex) {
        } finally {
          chatBox.setText("");
        }
      }
    });
    frame.addWindowListener(new WindowAdapter() {
      public void windowOpened(WindowEvent e) {
        chatBox.requestFocusInWindow();
      }
    });
    // --- Fim da inicialização da interface gráfica

    // Se for necessário adicionar código de inicialização ao
    // construtor, deve ser colocado aqui

  }

  // Método invocado sempre que o utilizador insere uma mensagem
  // na caixa de entrada
  public void newMessage(String message) throws IOException {
    outToServer.writeBytes(message + '\n');
  }

  // Método principal do objecto
  public void run() {
    final Thread inThread = new Thread() {
      @Override
      public void run() {
        boolean isGood = true;
        try {
          while (isGood) {
            String message = inFromServer.readLine();

            if (message == null) {
              isGood = false;
              continue;
            }
            message += '\n';

            printMessage(message);
          }
        } catch (IOException e) {
        } finally {
          frame.dispose();
          try {
            clientSocket.close();
          } catch (Exception e) {

          }
        }
      }
    };
    inThread.start();
  }

  // Instancia o ChatClient e arranca-o invocando o seu método run()
  // * NÃO MODIFICAR *
  public static void main(String[] args) throws IOException {
    ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
    client.run();
  }

}
