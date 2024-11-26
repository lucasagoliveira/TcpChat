import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

enum States {
    init,
    outside,
    inside
}

class User {
    private String name;
    private SocketChannel socket;
    private States state;
    private String room_name;
    public static Set<String> userNames = new HashSet<>();
    public static Map<SocketChannel, User> users = new HashMap<>();

    User(SocketChannel s) {
        name = null;
        socket = s;
        room_name = null;
        state = States.init;
    }

    String getName() {
        return name;
    }

    SocketChannel getSocket() {
        return socket;
    }

    States getState() {
        return state;
    }

    String getRoomName() {
        return room_name;
    }

    String changeName(String newName) throws Exception {
        if (userNames.contains(newName)) {
            throw new Exception("User name already exists");
        }
        String aux = name;
        name = newName;
        userNames.add(name);
        userNames.remove(aux);
        if (state == States.init) state = States.outside;
        return aux; // Can return NULL or the previous name if the user has already picked one before.
    }

    void join(String room_name) throws Exception {
        if (state == States.init) {
            throw new Exception("Username not initialized");
        }
        this.room_name = room_name;
        state = States.inside;
    }

    void leave() throws Exception {
        if (state != States.inside) {
            throw new Exception("User not in a room");
        }
        room_name = null;
        state = States.outside;
    }
}

class Room {
    private String name;
    private Integer nUsers;
    private List<User> users;
    public static Map<String, Room> rooms = new HashMap<>();

    Room(String name) {
        this.name = name;
        nUsers = 0;
        users = new ArrayList<>();
        rooms.put(name, this);
    }

    String getName() {
        return name;
    }

    Integer getNUsers() {
        return nUsers;
    }

    List<User> getUsers() {
        return users;
    }

    void disconnect(User user) throws Exception {
        user.leave();
        users.remove(user);
        nUsers--;
    }

    Boolean connect(User user) throws Exception {
        if (user.getState() == States.inside)
            rooms.get(user.getRoomName()).disconnect(user);
        users.add(user);
        nUsers++;
        user.join(this.name);
        return true;
    }
}

public class ChatServer {
    // A pre-allocated buffer for the received data
    static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

    // Decoder for incoming text -- assume UTF-8
    static private final Charset charset = Charset.forName("UTF8");
    static private final CharsetDecoder decoder = charset.newDecoder();

    public static void sendMessage(String message, User user) throws IOException {
        if (user == null || user.getSocket() == null) return;
        buffer.clear();
        buffer.put(message.getBytes(charset));
        buffer.flip();
        SocketChannel sc = user.getSocket();
        while (buffer.hasRemaining()) {
            sc.write(buffer);
        }
        System.out.println("Message sent: " + message);
    }

    public static void broadcast(String message, User user) {
        if (user.getRoomName() == null) return;
        for (var u : Room.rooms.get(user.getRoomName()).getUsers()) {
            try {
                sendMessage(message, u);
            } catch (IOException e) {
                System.err.println(e.getMessage());
            }
        }
    }

    public static void broadcastToRest(String message, User user) {
        if (user.getRoomName() == null) return;
        for (var u : Room.rooms.get(user.getRoomName()).getUsers()) {
            if (user == u) continue;
            try {
                sendMessage(message, u);
            } catch (IOException e) {
                System.err.println(e.getMessage());
            }
        }
    }

    public static void reply(String message, User user) {
        try {
            sendMessage(message, user);
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }

    public static void join(String roomName, User user) {
        if (user.getState() == States.inside) {
            broadcastToRest("LEFT " + user.getName(), user);
            Room room = Room.rooms.get(user.getRoomName());
        }
        Room room = Room.rooms.get(roomName);
        if (room == null)
            room = new Room(roomName);
        try {
            room.connect(user);
            reply("OK", user);
            broadcastToRest("JOINED " + user.getName(), user);
        } catch (Exception e) {
            reply("ERROR", user);
        }
    }

    public static void leave(User user) {
        broadcastToRest("LEFT " + user.getName(), user);
        Room room = Room.rooms.get(user.getRoomName());
        try {
            room.disconnect(user);
            reply("OK", user);
        } catch (Exception e) {
            reply("ERROR", user);
        }
    }

    public static void nick(String name, User user) {
        try {
            String oldName = user.changeName(name);
            reply("OK", user);
            if (user.getState() == States.inside) {
                broadcastToRest("NEWNICK " + oldName + " " + name, user);
            }
        } catch (Exception e) {
            reply("ERROR", user);
        }

    }

    public static void bye(User user) {
        if (user.getState() == States.inside) {
            try {
                broadcastToRest("LEFT " + user.getName(), user);
                Room.rooms.get(user.getRoomName()).disconnect(user);
            } catch (Exception e) {
                System.err.println(e.getMessage());
            }
        }
        reply("BYE", user);
        try {
            User.users.remove(user.getSocket());
            user.getSocket().close();
            user = null;
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }

    public static void message(String message, User user) {
        if (user.getState() == States.inside) {
            broadcast("MESSAGE " + user.getName() + " " + message, user);
        } else {
            reply("ERROR", user);
        }
    }

    public static void commands(String line, User user) throws Exception {
        line = line.trim();
        System.out.println(line);
        String[] args;
        if (line.startsWith("/nick")) {
            args = line.split(" ");
            if (args[1] == null)
                reply("ERROR", user);
            nick(args[1], user);
        } else if (line.startsWith("/join")) {
            args = line.split(" ");
            if (args[1] == null)
                reply("ERROR", user);
            join(args[1], user);
        } else if (line.startsWith("/leave")) {
            leave(user);
        } else if (line.startsWith("/bye")) {
            bye(user);
        } else if (line.startsWith("/")) {
            reply("ERROR", user);
        } else {
            message(line, user);
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

                        // It's an incoming connection.  Register this socket with
                        // the Selector so we can listen for input on it
                        Socket s = ss.accept();
                        System.out.println("Got connection from " + s);

                        // Make sure to make it non-blocking, so we can use a selector
                        // on it.
                        SocketChannel sc = s.getChannel();
                        sc.configureBlocking(false);

                        // Register it with the selector, for reading
                        sc.register(selector, SelectionKey.OP_READ);

                        // Register a new user
                        User.users.put(sc, new User(sc));

                    } else if (key.isReadable()) {

                        SocketChannel sc = null;

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
                            }

                        } catch (IOException ie) {
                            User user = User.users.get(sc);
                            broadcastToRest("LEFT " + user.getName(), user);
                            // On exception, remove this channel from the selector
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

                // We remove the selected keys, because we've dealt with them.
                keys.clear();
            }
        } catch (IOException ie) {
            System.err.println(ie);
        }
    }


    // Just read the message from the socket and send it to stdout
    static private boolean processInput(SocketChannel sc) throws IOException {
        // Read the message to the buffer
        buffer.clear();
        User user = User.users.get(sc);
        sc.read(buffer);
        buffer.flip();

        // If no data, close the connection
        if (buffer.limit() == 0) {
            return false;
        }

        // Decode and print the message to stdout
        String message = decoder.decode(buffer).toString();

        try {
            String[] inputs = message.split("\n");
            for (String input : inputs) {
                commands(input, user);
            }
        } catch (Exception e) {
            System.err.println(e);
        }

//        System.out.print(user.getName() + ": " + message);

        return true;
    }
}