import java.io.*;
import java.awt.*;
import java.awt.event.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.swing.*;


public class ChatClient {

    // Variáveis relacionadas com a interface gráfica --- * NÃO MODIFICAR *
    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextArea chatArea = new JTextArea();
    // --- Fim das variáveis relacionadas coma interface gráfica

    // Se for necessário adicionar variáveis ao objecto Client, devem
    // ser colocadas aqui

    // A pre-allocated buffer for the received data
    static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

    // Decoder for incoming text -- assume UTF-8
    static private final Charset charset = Charset.forName("UTF8");
    static private final CharsetDecoder decoder = charset.newDecoder();

    // Socket Channel
    private SocketChannel sc;

    // Selector
    private Selector selector;

    // Messages
    private ConcurrentLinkedQueue<String> messageQueue = new ConcurrentLinkedQueue<>();

    // Método a usar para acrescentar uma string à caixa de texto
    // * NÃO MODIFICAR *
    public void printMessage(final String message) {
        chatArea.append(message);
    }


    // Construtor
    public ChatClient(String server, int port) throws IOException {

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
        selector = Selector.open();
        sc = SocketChannel.open();
        sc.configureBlocking(false);
        InetSocketAddress isa = new InetSocketAddress(server, port);
        sc.connect(isa);
        sc.register(selector, SelectionKey.OP_CONNECT);
    }


    // Método invocado sempre que o utilizador insere uma mensagem
    // na caixa de entrada
    public void newMessage(String message) throws IOException {
        // PREENCHER AQUI com código que envia a mensagem ao servidor
        messageQueue.offer(message+"\n");
    }

    public void readMessage() throws IOException {
        buffer.clear();
        sc.read(buffer);
        buffer.flip();

        // If no data, close the connection
        if (buffer.limit() == 0) {
            return;
        }

        // Decode and print the message to stdout
        String message = decoder.decode(buffer).toString();
        System.out.println(message);
        printMessage(message+"\n");
    }

    // Método principal do objecto
    public void run() throws IOException {
        try {
            while (true) {
                int num = selector.select();

                // If we don't have any activity, loop around and wait again
                if (num == 0) {
                    continue;
                }
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();

                SocketChannel channel;

                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    channel = (SocketChannel) key.channel();

                    if (key.isConnectable()) {
                        if (channel.isConnectionPending()) {
                            channel.finishConnect();
                        }
                        channel.register(selector, SelectionKey.OP_WRITE);
                    } else if (key.isReadable()) {
                        readMessage();
                    } else if (key.isWritable()) {
                        String message = messageQueue.poll();
                        buffer.clear();
                        if (message != null) {
                            buffer.put(message.getBytes(charset));
                            buffer.flip();
                            while (buffer.hasRemaining()) {
                                sc.write(buffer);
                            }
                        }
                        channel.register(key.selector(), SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (sc != null) sc.close();
                if (selector != null) selector.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    // Instancia o Client e arranca-o invocando o seu método run()
    // * NÃO MODIFICAR *
    public static void main(String[] args) throws IOException {
        ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
        client.run();
    }

}