package com.leafuke.deathrewind.knotlink;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class SignalSubscriber {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TcpClient knotLinkSubscriber;
    private final String appId;
    private final String signalId;
    private SignalListener signalListener;

    public interface SignalListener {
        void onSignalReceived(String data);
    }

    public SignalSubscriber(String appId, String signalId) {
        this.appId = appId;
        this.signalId = signalId;
    }

    public void setSignalListener(SignalListener listener) {
        this.signalListener = listener;
    }

    public void start() {
        knotLinkSubscriber = new TcpClient();
        if (knotLinkSubscriber.connectToServer("127.0.0.1", 6372)) {
            knotLinkSubscriber.setDataReceivedListener(data -> {
                LOGGER.debug("Received KnotLink event: {}", data);
                if (signalListener != null) {
                    signalListener.onSignalReceived(data);
                }
            });

            String subscriptionKey = appId + "-" + signalId;
            knotLinkSubscriber.sendData(subscriptionKey);
            LOGGER.info("SignalSubscriber started and subscribed to {}.", subscriptionKey);
        } else {
            LOGGER.error("SignalSubscriber failed to start.");
        }
    }

    public void stop() {
        if (knotLinkSubscriber != null) {
            knotLinkSubscriber.close();
            LOGGER.info("SignalSubscriber stopped.");
        }
    }
}
