package raknetserver.utils;

public class Constants {

    public static final int MAX_PACKET_LOSS = Integer.parseInt(System.getProperty("raknetserver.maxPacketLoss", "10240"));
    public static final int MAX_PACKET_SPLITS = Integer.parseInt(System.getProperty("raknetserver.maxPacketSplits", "4096"));

    public static final int RTO_INTERVAL = 100;

    public static final int RTO_MIN = RTO_INTERVAL * 2;
    public static final int RTO_DEF = RTO_MIN + RTO_INTERVAL;
    public static final int RTO_MAX = RTO_MIN * 300;
    public static final int RTO_INIT_APPEND = RTO_MIN / 8;

    public static final int SND_WINDOW = 128;
    public static final int ACK_FAST_RESENT = 3;
    public static final int MAX_RETRANSMISSION = 20;

    public static final int FLUSH_INTERVAL = RTO_INTERVAL / 10;
}
