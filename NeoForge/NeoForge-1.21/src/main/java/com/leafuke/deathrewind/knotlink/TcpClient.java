package com.leafuke.deathrewind.knotlink;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TcpClient {
    private static final Logger LOGGER = LogUtils.getLogger();

    private Socket socket;
    private PrintWriter out;
    private InputStream in;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final String heartbeatMessage = "heartbeat";
    private final String heartbeatResponse = "heartbeat_response";
    private boolean running = false;

    public interface DataReceivedListener {
        void onDataReceived(String data);
    }

    private DataReceivedListener dataReceivedListener;

    public boolean connectToServer(String host, int port) {
        try {
            socket = new Socket(host, port);
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            in = socket.getInputStream();

            LOGGER.info("Connected to KnotLink server at {}:{}", host, port);
            Thread reader = new Thread(this::readData, "deathrewind-knotlink-reader");
            reader.setDaemon(true);
            reader.start();

            startHeartbeat();
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to connect to KnotLink server: {}", e.getMessage());
            return false;
        }
    }

    public void sendData(String data) {
        if (out == null) {
            LOGGER.warn("Socket is not connected.");
            return;
        }
        out.print(data);
        out.flush();
    }

    public void setDataReceivedListener(DataReceivedListener listener) {
        this.dataReceivedListener = listener;
    }

    public void close() {
        stopHeartbeat();
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            LOGGER.warn("Error closing socket: {}", e.getMessage());
        }
    }

    private void startHeartbeat() {
        running = true;
        scheduler.scheduleAtFixedRate(() -> {
            if (running) {
                sendData(heartbeatMessage);
            }
        }, 1L, 3L, TimeUnit.MINUTES);
    }

    private void stopHeartbeat() {
        running = false;
        scheduler.shutdown();
    }

    private void readData() {
        try {
            byte[] buffer = new byte[1024];
            while (true) {
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break;
                }

                String receivedData = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                if (receivedData.trim().equals(heartbeatResponse)) {
                    continue;
                }

                if (dataReceivedListener != null) {
                    dataReceivedListener.onDataReceived(receivedData);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("KnotLink socket error: {}", e.getMessage());
        } finally {
            stopHeartbeat();
            LOGGER.info("KnotLink server disconnected.");
        }
    }
}
