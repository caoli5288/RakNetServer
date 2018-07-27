package raknetserver.pipeline.raknet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.RecyclableArrayList;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.raknet.RakNetEncapsulatedData;
import raknetserver.packet.raknet.RakNetPacket;
import raknetserver.packet.raknet.RakNetReliability.REntry;
import raknetserver.packet.raknet.RakNetReliability.RakNetACK;
import raknetserver.packet.raknet.RakNetReliability.RakNetNACK;
import raknetserver.utils.Constants;
import raknetserver.utils.PacketHandlerRegistry;
import raknetserver.utils.UINT;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

public class RakNetPacketReliabilityHandler extends ChannelDuplexHandler {

	protected static final PacketHandlerRegistry<RakNetPacketReliabilityHandler, RakNetPacket> REGISTRY = new PacketHandlerRegistry<>();
	protected static final int HALF_WINDOW = UINT.B3.MAX_VALUE / 2;

	static {
		REGISTRY.register(RakNetEncapsulatedData.class, (ctx, handler, packet) -> handler.handleRakNetEncapsulatedData(ctx, packet));
		REGISTRY.register(RakNetACK.class, (ctx, handler, packet) -> handler.handleRakNetACK(ctx, packet));
		REGISTRY.register(RakNetNACK.class, (ctx, handler, packet) -> handler.handleRakNetNACK(ctx, packet));
	}

	protected final HashMap<Integer, RakNetEncapsulatedData> sentPackets = new HashMap<>();
	private final Queue<RakNetEncapsulatedData> sendque = new LinkedList<>();

	protected int lastReceivedSeqId = -1;
	protected int currentRakSeqID = -1;
	private int rxrto = Constants.RTO_DEF;
	private int srtt;
	private int rxrtt;
	private ScheduledFuture<?> rtotask;

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		int interval = Constants.RTO_INTERVAL;
		rtotask = ctx.channel().eventLoop().scheduleAtFixedRate(() -> processRTOTimeout(ctx), interval, interval, TimeUnit.MILLISECONDS);
		super.channelActive(ctx);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		rtotask.cancel(false);
		super.channelInactive(ctx);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof RakNetPacket) {
			REGISTRY.handle(ctx, this, (RakNetPacket) msg);
			return;
		}
		super.channelRead(ctx, msg);
	}

	private void processRTOTimeout(ChannelHandlerContext ctx) {
		if (sentPackets.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		RecyclableArrayList list = RecyclableArrayList.newInstance();
		try {
			for (RakNetEncapsulatedData data : sentPackets.values()) {
				if (data.isRTOTimeout(now)) {
					list.add(data);
				}
			}
			if (!list.isEmpty()) {
				for (Object obj : list) {
					resendPacket(ctx, ((RakNetEncapsulatedData) obj).getSeqId());
				}
				ctx.flush();
			}
		} finally {
			list.recycle();
		}
	}

	private void resendPacket(ChannelHandlerContext ctx, int id) {
		RakNetEncapsulatedData packet = sentPackets.remove(id);
		if (packet != null) {
			sendRakPacket(ctx, packet, false);
		}
	}

	protected void sendRakPacket(ChannelHandlerContext ctx, RakNetEncapsulatedData pk, boolean flush) {
		pk.setSeqId(getNextRakSeqID());
		pk.updateRTO(rxrto);
		sentPackets.put(pk.getSeqId(), pk);
		ctx.write(pk);
		if (flush) {
			ctx.flush();
		}
	}

	protected int getNextRakSeqID() {
		return (currentRakSeqID = UINT.B3.plus(currentRakSeqID, 1));
	}

	public void handleRakNetNACK(ChannelHandlerContext ctx, RakNetNACK packet) {
		for (REntry entry : packet.getEntries()) {
			resendRakNetPackets(ctx, entry.idstart, entry.idfinish);
		}
		flushQueue(ctx);
	}

	public void handleRakNetACK(ChannelHandlerContext ctx, RakNetACK packet) {
		for (REntry entry : packet.getEntries()) {
			confirmRakNetPackets(entry.idstart, entry.idfinish);
		}
		flushQueue(ctx);
	}

	private void confirmRakNetPackets(int idstart, int idfinish) {
		for (int id = idstart; id <= idfinish; id++) {
			RakNetEncapsulatedData pk = sentPackets.remove(id);
			if (pk != null) {
				updateRxRTO(pk.getRTT());
			}
		}
	}

	private void flushQueue(ChannelHandlerContext ctx) {
		boolean needFlush = false;
		while (!sendque.isEmpty() && sentPackets.size() < Constants.SND_WINDOW) {
			sendRakPacket(ctx, sendque.remove(), false);
			needFlush = true;
		}
		if (needFlush) {
			ctx.flush();
		}
	}

	private void updateRxRTO(int rtt) {
		if (srtt == 0) {
			srtt = rtt;
			rxrtt = rtt / 2;
		} else {
			int delta = rtt - srtt;
			if (delta < 0) delta = -delta;
			rxrtt = (3 * rxrtt + delta) / 4;
			srtt = (7 * srtt + rtt) / 8;
			if (srtt < 1) srtt = 1;
		}
		rxrto = bound(Constants.RTO_MIN, srtt + Math.max(Constants.RTO_INTERVAL, 4 * rxrtt), Constants.RTO_MAX);
	}

	private int bound(int lower, int middle, int upper) {
		return Math.min(Math.max(lower, middle), upper);
	}

	public void handleRakNetEncapsulatedData(ChannelHandlerContext ctx, RakNetEncapsulatedData packet) {
		//check for missing packets
		int packetSeqId = packet.getSeqId();
		int seqIdDiff = UINT.B3.minus(packetSeqId, lastReceivedSeqId);
		//ignore duplicate packet
		if ((seqIdDiff == 0) || (seqIdDiff > HALF_WINDOW)) {
			return;
		}
		//send nack for missed packets
		if (seqIdDiff > 1) {
			ctx.writeAndFlush(new RakNetNACK(UINT.B3.plus(lastReceivedSeqId, 1), UINT.B3.minus(packetSeqId, 1))).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
		}

		lastReceivedSeqId = packetSeqId;
		//if id is after last received, which means that we don't have any missing packets, send ACK for it

		ctx.writeAndFlush(new RakNetACK(packetSeqId)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
		//add encapsulated packet
		for (EncapsulatedPacket encapsulated : packet.getPackets()) {
			ctx.fireChannelRead(encapsulated);
		}
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) throws Exception {
		if (packet instanceof EncapsulatedPacket) {
			RakNetEncapsulatedData pk = new RakNetEncapsulatedData(((EncapsulatedPacket) packet));
			if (sentPackets.size() < Constants.SND_WINDOW) {
				sendRakPacket(ctx, pk, true);
			} else {
				sendque.add(pk);
			}
		} else {
			super.write(ctx, packet, promise);
		}
	}

	private void resendRakNetPackets(ChannelHandlerContext ctx, int idstart, int idfinish) {
		for (int id = idstart; id <= idfinish; id++) {
			resendPacket(ctx, id);
		}
	}
}
