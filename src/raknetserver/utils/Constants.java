package raknetserver.utils;

public class Constants {

	public static final int MAX_PACKET_LOSS = Integer.parseInt(System.getProperty("raknetserver.maxPacketLoss", "10240"));
	public static final int MAX_PACKET_SPLITS = Integer.parseInt(System.getProperty("raknetserver.maxPacketSplits", "4096"));

	public static final int RTO_INTERVAL = 100;

	public static final int RTO_MIN = RTO_INTERVAL * 2;
	public static final int RTO_DEF = RTO_MIN + RTO_INTERVAL;
	public static final int RTO_MAX = RTO_MIN * 300;

	public static final int SND_WINDOW = 128;
}
