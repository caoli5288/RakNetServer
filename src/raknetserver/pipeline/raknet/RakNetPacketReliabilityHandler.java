package raknetserver.pipeline.raknet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.raknet.RakNetEncapsulatedData;
import raknetserver.packet.raknet.RakNetPacket;
import raknetserver.packet.raknet.RakNetReliability.REntry;
import raknetserver.packet.raknet.RakNetReliability.RakNetACK;
import raknetserver.packet.raknet.RakNetReliability.RakNetNACK;
import raknetserver.utils.Constants;
import raknetserver.utils.PacketHandlerRegistry;
import raknetserver.utils.RingBuf;
import raknetserver.utils.UInt;

import java.util.LinkedList;
import java.util.Queue;

public class RakNetPacketReliabilityHandler extends ChannelDuplexHandler {

    protected static final PacketHandlerRegistry<RakNetPacketReliabilityHandler, RakNetPacket> REGISTRY = new PacketHandlerRegistry<>();

    static {
        REGISTRY.register(RakNetEncapsulatedData.class, (ctx, handler, packet) -> handler.handleRakNetEncapsulatedData(ctx, packet));
        REGISTRY.register(RakNetACK.class, (ctx, handler, packet) -> handler.handleRakNetACK(ctx, packet));
        REGISTRY.register(RakNetNACK.class, (ctx, handler, packet) -> handler.handleRakNetNACK(ctx, packet));
    }

    private final Queue<RakNetEncapsulatedData> sendQue = new LinkedList<>();
    private final RingBuf<RakNetEncapsulatedData> sndWindow = new RingBuf<>(8);// capacity 256
    protected int receiveId;

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        sendQue.clear();
        sndWindow.resetFully();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RakNetPacket) {
            REGISTRY.handle(ctx, this, (RakNetPacket) msg);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    private void resendPacket(ChannelHandlerContext ctx, int id) {
        if (!sndWindow.contains(id)) {
            return;
        }
        sendRakPacket(ctx, sndWindow._look(id));
    }

    public void handleRakNetNACK(ChannelHandlerContext ctx, RakNetNACK packet) {
        for (REntry entry : packet.getEntries()) {
//            System.out.println("!!! recv nack start=" + entry.idstart + ", end=" + entry.idfinish);
            resendRakNetPackets(ctx, entry.idstart, entry.idfinish);
        }
        flushQueue(ctx);
    }

    public void handleRakNetACK(ChannelHandlerContext ctx, RakNetACK packet) {
        for (REntry entry : packet.getEntries()) {
            handleAckEntry(ctx, entry);
        }
        flushQueue(ctx);
    }

    private void handleAckEntry(ChannelHandlerContext ctx, REntry entry) {
        if (entry.idfinish < sndWindow.first()) {// outdated ack msg
            return;
        }
//        System.out.println("!!! ack_begin=" + entry.idstart + ", end=" + entry.idfinish + ", snd_window_head=" + sndWindow.first() + ", tail=" + sndWindow.next());
        sndWindow.walk(sndWindow.first(), entry.idfinish + 1, (id, ele) -> {
            if (ele.isAck() || id != ele.getId()) {// already changed
//                System.out.println("!!! ack ignore=" + id + ", ack=" + ele.isAck() + ", id_change=" + (id != ele.getId()));
                return;
            }
            if (id < entry.idstart) {
                int skip = ele.skip();
                if (skip >= Constants.FAST_RESENT) {
//                    System.out.println("!!! fast rst=" + id);
                    sendRakPacket(ctx, ele);
                }
                return;
            }
            ele.setAck(true);
        });
    }

    private void recycle() {
        for (RakNetEncapsulatedData head; (head = sndWindow.head()) != null; ) {
            if (head.getId() == sndWindow.first() && !head.isAck()) {
                return;
            }
//            System.out.println("!!! recycle packet " + sndWindow.first() + ", window length=" + sndWindow.length());
            sndWindow._shift();
        }
    }

    private void flushQueue(ChannelHandlerContext ctx) {
        recycle();// recycle first
        while (!sendQue.isEmpty() && sndWindow.length() < Constants.SND_WINDOW) {
            sendRakPacket(ctx, sendQue.remove());
        }
    }

    protected void sendRakPacket(ChannelHandlerContext ctx, RakNetEncapsulatedData data) {
        int id = sndWindow.add(data);
        data.setId(id);
        ctx.writeAndFlush(data, ctx.voidPromise());
    }

    public void handleRakNetEncapsulatedData(ChannelHandlerContext ctx, RakNetEncapsulatedData packet) {
        //check for missing packets
        int id = packet.getId();
        int delta = UInt.U24.delta(receiveId, id);
        //ignore duplicate packet
        if (delta <= 0 || delta > UInt.U16.MAX_VALUE) {
            return;
        }
        //send nack for missed packets
        if (delta > 1) {
//            System.out.println("!!! send nack");
            ctx.writeAndFlush(new RakNetNACK(UInt.U24.mod(receiveId, 1), UInt.U24.mod(id, -1))).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }

        receiveId = id;
        //if id is after last received, which means that we don't have any missing packets, send ACK for it

        ctx.writeAndFlush(new RakNetACK(id)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        //add encapsulated packet
        for (EncapsulatedPacket encapsulated : packet.getPackets()) {
            ctx.fireChannelRead(encapsulated);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof EncapsulatedPacket) {
            RakNetEncapsulatedData data = new RakNetEncapsulatedData((EncapsulatedPacket) msg);
            if (sndWindow.length() < Constants.SND_WINDOW) {
                sendRakPacket(ctx, data);
            } else {
                sendQue.add(data);
            }
        } else {
            ctx.write(msg, promise);
        }
    }

    private void resendRakNetPackets(ChannelHandlerContext ctx, int idstart, int idfinish) {
        for (int id = idstart; id <= idfinish; id++) {
            resendPacket(ctx, id);
        }
    }
}
