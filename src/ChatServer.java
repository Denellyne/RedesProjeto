import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

public class ChatServer {

  private static class User {
    private String str = "";
    private int idx = 0;

    public boolean writeToBuffer(String message) {
      if (idx + message.length() >= 16384)
        return false;

      str += message;
      idx += message.length();

      return true;
    }

    public void clearBuffer() {
      str = "";
      idx = 0;
    }

    public String getString() {
      return str;
    }

  }

  private static class Room {
    private final List<String> messages = new ArrayList<>();
    private final List<String> messagesErrors = new ArrayList<>();
    private String name;
    private Integer clientCount = 0;

    Room(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public void addClient() {
      clientCount++;
    }

    public Integer removeClient(String nickname) {
      clientCount--;
      addMessage(String.format("LEFT %s\n", nickname));
      return clientCount;
    }

    public Integer removeClientError(String nickname) {
      clientCount--;
      addMessageErrors(String.format("LEFT %s\n", nickname));
      return clientCount;
    }

    public void addMessage(String message) {
      messages.add(message);
    }

    private void addMessageErrors(String message) {
      messagesErrors.add(message);
    }

    public void clearMessages() {
      messages.clear();
    }

    public void enqueueErrors() {
      for (String str : messagesErrors)
        addMessage(str);
      messagesErrors.clear();
    }

    public boolean writeToSocket(SocketChannel sc) throws IOException {

      for (String msg : messages) {

        ByteBuffer buf = prepareStringForSocket(msg);

        int totalWrite = 0;
        int totalSize = buf.remaining();

        while (buf.hasRemaining())
          totalWrite += sc.write(buf);
        if (totalSize != totalWrite)
          return false;
      }
      return true;
    }

  }

  static private Room newRoom(String name) {
    Room room = new Room(name);
    room.addClient();
    return room;
  }

  static private User newUser() {
    User usr = new User();
    return usr;
  }

  // A pre-allocated buffer for the received data
  static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

  static private final HashMap<String, SelectableChannel> names = new HashMap<>();
  static private final HashMap<SelectableChannel, String> clients = new HashMap<>();
  static private final HashMap<String, Room> rooms = new HashMap<>();
  static private final HashMap<SelectableChannel, User> users = new HashMap<>();
  // Decoder for incoming text -- assume UTF-8
  static private final Charset charset = Charset.forName("UTF8");
  static private final CharsetDecoder decoder = charset.newDecoder();
  static private final List<String> messages = new ArrayList<>();

  static private void closeConnection(SelectionKey key) {

    SocketChannel sc = (SocketChannel) key.channel();
    String nickname = null;
    for (String str : names.keySet()) {
      if (names.get(str) == sc) {
        nickname = str;
        names.remove(nickname);
        break;
      }
    }
    if (clients.get(sc) != null) {
      Room room = rooms.get(clients.get(sc));
      clients.remove(sc);
      if (room.removeClientError(nickname) == 0)
        rooms.remove(room.getName());
    }
    if (users.get(sc) != null) {
      users.remove(sc);
    }
  }

  static public void main(String args[]) throws Exception {
    // Parse port from command line
    int port = Integer.parseInt(args[0]);

    try {
      // Instead of creating a ServerSocket, create a ServerSocketChannel
      ServerSocketChannel ssc = ServerSocketChannel.open();

      // Set it to non-blocking, so we can use select
      ssc.configureBlocking(false);

      // Get the Socket connected to this channel, and bind it to the
      // listening port
      ServerSocket ss = ssc.socket();
      InetSocketAddress isa = new InetSocketAddress(port);
      ss.bind(isa);

      // Create a new Selector for selecting
      Selector selector = Selector.open();

      // Register the ServerSocketChannel, so we can listen for incoming
      // connections
      ssc.register(selector, SelectionKey.OP_ACCEPT);
      System.out.println("Listening on port " + port);

      while (true) {
        // See if we've had any activity -- either an incoming connection,
        // or incoming data on an existing connection
        int num = selector.select();

        // If we don't have any activity, loop around and wait again
        if (num == 0) {
          continue;
        }

        // Get the keys corresponding to the activity that has been
        // detected, and process them one by one
        Set<SelectionKey> keys = selector.selectedKeys();
        Iterator<SelectionKey> it = keys.iterator();
        while (it.hasNext()) {
          // Get a key representing one of bits of I/O activity
          SelectionKey key = it.next();

          // What kind of activity is it?
          if (key.isAcceptable()) {

            // It's an incoming connection. Register this socket with
            // the Selector so we can listen for input on it
            Socket s = ss.accept();

            System.out.println("Got connection from " + s);
            // messages.add(String.format("JOINED %s\n", ss.toString()));

            // Make sure to make it non-blocking, so we can use a selector
            // on it.
            SocketChannel sc = s.getChannel();
            sc.configureBlocking(false);

            // Register it with the selector, for reading
            sc.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

          }
          if (processKey(key) == false)
            closeConnection(key);

        }

        broadCastMessages(keys);
        keys.clear();
      }
    } catch (IOException ie) {
      System.err.println(ie);
    }
  }

  static private boolean processKey(SelectionKey key) {

    SocketChannel sc = null;
    if (key.isValid() == false)
      return false;

    if (key.isReadable()) {

      try {

        // It's incoming data on a connection -- process it
        sc = (SocketChannel) key.channel();
        boolean ok = processInput(sc);

        // If the connection is dead, remove it from the selector
        // and close it
        if (!ok) {
          key.cancel();

          Socket s = null;
          try {
            s = sc.socket();
            System.out.println("Closing connection to " + s);
            s.close();
          } catch (IOException ie) {
            System.err.println("Error closing socket " + s + ": " + ie);
          }
          return false;
        }

      } catch (IOException ie) {

        // On exception, remove this channel from the selector
        key.cancel();

        try {
          sc.close();
        } catch (IOException ie2) {
          System.out.println(ie2);
        }

        System.out.println("Closed " + sc);
        return false;
      }
      sc = null;
      return true;
    }
    sc = null;
    return true;
  }

  static private ByteBuffer prepareStringForSocket(String message) {
    return ByteBuffer.wrap(message.getBytes());
  }

  static private boolean processInputEx(SocketChannel sc, String message) throws IOException {
    System.out.println(message);

    String nickname = null;
    for (String str : names.keySet()) {
      if (names.get(str) == sc) {
        nickname = str;
        break;
      }
    }

    if (message.startsWith("/bye")) {
      Room room = rooms.get(clients.get(sc));
      if (clients.get(sc) != null) {
        clients.remove(sc);
        if (rooms != null && room.removeClient(nickname) == 0) {
          System.out.printf("room %s closed\n", room.getName());
          rooms.remove(room.getName());
        }
      }
      boolean ack = writeToSocket(sc, "BYE\n");
      return !ack;
    }
    if (message.startsWith("/nick")) {
      if (message.split(" ").length < 2)
        return writeToSocket(sc, "ERROR\n");

      String name = message.split(" ")[1];
      String answer = new String();
      if (names.get(name) == null) {
        if (nickname != null)
          names.remove(nickname);

        names.put(name, (SelectableChannel) sc);
        answer = "OK\n";
        Room room = rooms.get(clients.get(sc));
        if (room != null)
          room.addMessage(String.format("NEWNICK %s %s\n", nickname, name));
      } else
        answer = "ERROR\n";

      return writeToSocket(sc, answer);
    }
    if (nickname == null)
      return writeToSocket(sc, "ERROR\n");

    if (message.startsWith("/priv")) {
      String[] splitMessage = message.split(" ");
      if (splitMessage.length < 2)
        return writeToSocket(sc, "ERROR\n");

      String msg = message
          .substring(splitMessage[0].length() + 1 + splitMessage[1].length() + 1);
      String answer = new String();
      String usr2 = splitMessage[1];
      msg = "PRIVATE " + nickname + " " + msg + '\n';
      if (names.get(usr2) != null) {
        answer = "OK\n";
        return writeToSocket(sc, answer) &&
            writeToSocket((SocketChannel) names.get(usr2), msg);
      } else
        answer = "ERROR\n";

      return writeToSocket(sc, answer);
    }

    if (message.startsWith("/join")) {
      if (message.split(" ").length < 2)
        return writeToSocket(sc, "ERROR\n");
      if (clients.get(sc) != null) {
        Room room = rooms.get(clients.get(sc));
        clients.remove(sc);
        if (room.removeClient(nickname) == 0) {

          System.out.printf("room %s closed\n", room.getName());
          rooms.remove(room.getName());
        }
      }
      String name = message.split(" ")[1];
      String answer = "OK\n";

      Room room = rooms.get(name);
      if (room == null)
        room = newRoom(name);
      else
        room.addClient();
      rooms.put(name, room);
      clients.put(sc, name);
      room.addMessage(String.format("JOINED %s\n", nickname));

      return writeToSocket(sc, answer);
    }

    Room room = rooms.get(clients.get(sc));
    if (room == null)
      return writeToSocket(sc, "ERROR\n");

    if (message.startsWith("/leave")) {
      if (clients.get(sc) != null) {
        clients.remove(sc);
        if (room.removeClient(nickname) == 0) {
          System.out.printf("room %s closed\n", room.getName());
          rooms.remove(room.getName());
        }
      }
      return writeToSocket(sc, "OK\n");
    }
    if (message.startsWith("//"))
      message = message.substring(1);
    String preparedMessage = String.format("MESSAGE %s:%s\n", nickname, message);
    System.out.println(preparedMessage);
    room.addMessage(preparedMessage);
    return true;
  }

  // Just read the message from the socket and send it to stdout
  static private boolean processInput(SocketChannel sc) throws IOException {
    // Read the message to the buffer
    buffer.clear();
    sc.read(buffer);
    buffer.flip();

    // If no data, close the connection
    if (buffer.limit() == 0) {
      return false;
    }

    // Decode and print the message to stdout
    String message = decoder.decode(buffer).toString();
    if (message.endsWith("\n")) {

      User usr = users.get(sc);
      if (usr == null) {
        usr = newUser();
        users.put(sc, usr);
      }
      boolean ret = usr.writeToBuffer(message);
      if (ret == false)
        return false;
      message = usr.getString();
      usr.clearBuffer();
      // message = message.substring(0, message.length() - 1);
    } else {
      User usr = users.get(sc);
      if (usr == null) {
        usr = newUser();
        users.put(sc, usr);
      }
      boolean ret = usr.writeToBuffer(message);
      if (ret == false)
        return false;
      return true;
    }

    String[] messages = message.split("\n");
    for (String msg : messages) {
      if (!processInputEx(sc, msg))
        return false;
    }

    // if (message.isEmpty())
    // return true;

    return true;
  }

  static private boolean broadCastMessages(Set<SelectionKey> keys) {
    if (keys.isEmpty())
      return false;
    Iterator<SelectionKey> it = keys.iterator();
    boolean hadErrors = false;
    while (it.hasNext()) {

      SelectionKey key = it.next();

      SocketChannel sc = null;
      if (key.isValid() == false)
        continue;
      if (key.isWritable()) {

        try {

          sc = (SocketChannel) key.channel();
          Room room = rooms.get(clients.get(sc));
          if (room == null)
            continue;
          if (room.writeToSocket(sc) == false) {
            key.cancel();
            sc.close();
            hadErrors = true;
            closeConnection(key);
          }

        } catch (IOException ie) {
          key.cancel();

          try {
            sc.close();
          } catch (IOException ie2) {
            System.out.println(ie2);
          }

          System.out.println("Closed " + sc);
        }
      }
    }
    for (Room room : rooms.values()) {
      room.clearMessages();
      room.enqueueErrors();
    }

    return hadErrors;

  }

  static private boolean broadCastMessagesE(Set<SelectionKey> keys) {
    if (messages.isEmpty() || keys.isEmpty())
      return false;
    Iterator<SelectionKey> it = keys.iterator();

    final List<String> messagesErrors = new ArrayList<>();
    while (it.hasNext()) {

      SelectionKey key = it.next();

      SocketChannel sc = null;
      if (key.isValid() == false)
        continue;
      if (key.isWritable()) {

        try {

          sc = (SocketChannel) key.channel();
          for (String message : messages) {
            boolean ok = writeToSocket(sc, message);

            // If the connection is dead, remove it from the selector
            // and close it
            if (!ok) {
              messagesErrors.add(String.format("LEFT %s\n", sc.toString()));
              key.cancel();

              Socket s = null;
              try {
                s = sc.socket();
                System.out.println("Closing connection to " + s);
                s.close();
              } catch (IOException ie) {
                System.err.println("Error closing socket " + s + ": " + ie);
              }
            }

          }

        } catch (IOException ie) {
          messagesErrors.add(String.format("LEFT %s\n", sc.toString()));
          key.cancel();

          try {
            sc.close();
          } catch (IOException ie2) {
            System.out.println(ie2);
          }

          System.out.println("Closed " + sc);
        }
      }
    }

    messages.clear();
    for (String str : messagesErrors)
      messages.add(str);
    return messages.size() != 0;
  }

  static private boolean writeToSocket(SocketChannel sc, String message) throws IOException {

    ByteBuffer buf = prepareStringForSocket(message);

    int totalWrite = 0;
    int totalSize = buf.remaining();

    while (buf.hasRemaining())
      totalWrite += sc.write(buf);

    return totalWrite == totalSize;
  }
}
