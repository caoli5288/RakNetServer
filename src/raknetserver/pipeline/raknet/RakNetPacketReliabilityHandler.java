package raknetserver.pipeline.raknet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.RecyclableArrayList;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.raknet.RakNetEncapsulatedData;
import raknetserver.packet.raknet.RakNetReliability.REntry;
import raknetserver.packet.raknet.RakNetReliability.RakNetACK;
import raknetserver.packet.raknet.RakNetReliability.RakNetNACK;
import raknetserver.utils.Constants;
import raknetserver.utils.UINT;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

//TODO: figure out if seq numbers can wrap
public class RakNetPacketReliabilityHandler extends ChannelDuplexHandler {

	protected static final int HALF_WINDOW = UINT.B3.MAX_VALUE / 2;

	protected final HashMap<Integer, RakNetEncapsulatedData> sentPackets = new HashMap<>();
	private final Queue<RakNetEncapsulatedData> sendque = new LinkedList<>();

	protected int lastReceivedSeqId = -1;

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

	private void processRTOTimeout(ChannelHandlerContext ctx) {
		if (!sentPackets.isEmpty()) {
			RecyclableArrayList list = RecyclableArrayList.newInstance();
			try {
				for (RakNetEncapsulatedData pk : sentPackets.values()) {
					if (pk.isRTOTimeout()) {
						list.add(pk);
					}
				}
				if (!list.isEmpty()) list.forEach(ele -> resendPacket(ctx, ((RakNetEncapsulatedData) ele).getSeqId()));
			} finally {
				list.recycle();
			}
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

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object packet) throws Exception {
		if (packet instanceof RakNetEncapsulatedData) {
			RakNetEncapsulatedData edata = (RakNetEncapsulatedData) packet;
			//check for missing packets
			int packetSeqId = edata.getSeqId();
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
			edata.getPackets().forEach(ctx::fireChannelRead);
		} else if (packet instanceof RakNetACK) {
			for (REntry entry : ((RakNetACK) packet).getEntries()) {
				confirmRakNetPackets(entry.idstart, entry.idfinish);
			}
			flushQueue(ctx);
		} else if (packet instanceof RakNetNACK) {
			for (REntry entry : ((RakNetNACK) packet).getEntries()) {
				resendRakNetPackets(ctx, entry.idstart, entry.idfinish);
			}
			flushQueue(ctx);
		} else {
			super.channelRead(ctx, packet);
		}
	}

	private void flushQueue(ChannelHandlerContext ctx) {
		while (!sendque.isEmpty() && sentPackets.size() < Constants.SND_WINDOW) {
			sendRakNetPacket(ctx, sendque.remove(), true);
		}
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) throws Exception {
		if (packet instanceof EncapsulatedPacket) {
			RakNetEncapsulatedData pk = new RakNetEncapsulatedData(((EncapsulatedPacket) packet));
			if (sentPackets.size() < Constants.SND_WINDOW) {
				initRakNetPacket(pk);
				ctx.writeAndFlush(pk);
			} else {
				sendque.add(pk);
			}
		} else {
			ctx.writeAndFlush(packet, promise);
		}
	}

	private void confirmRakNetPackets(int idstart, int idfinish) {
		for (int id = idstart; id <= idfinish; id++) {
			RakNetEncapsulatedData pk = sentPackets.remove(id);
			if (pk != null) {
				updateRxRTO(pk.getRTT());
			}
		}
	}

	private void resendRakNetPackets(ChannelHandlerContext ctx, int idstart, int idfinish) {
		for (int id = idstart; id <= idfinish; id++) {
			resendPacket(ctx, id);
		}
	}

	private void resendPacket(ChannelHandlerContext ctx, int id) {
		RakNetEncapsulatedData packet = sentPackets.remove(id);
		if (packet != null) {
			sendRakNetPacket(ctx, packet, false);
		}
	}

	protected void initRakNetPacket(RakNetEncapsulatedData pk) {
		pk.setSeqId(getNextRakSeqID());
		pk.updateRTO(rxrto);
		sentPackets.put(pk.getSeqId(), pk);
	}

	protected void sendRakNetPacket(ChannelHandlerContext ctx, RakNetEncapsulatedData pk, boolean direct) {
		if (direct || sentPackets.size() < Constants.SND_WINDOW) {
			initRakNetPacket(pk);
			ctx.writeAndFlush(pk);
		} else {
			sendque.add(pk);
		}
	}

	protected int currentRakSeqID = -1;

	protected int getNextRakSeqID() {
		return (currentRakSeqID = UINT.B3.plus(currentRakSeqID, 1));
	}
}
