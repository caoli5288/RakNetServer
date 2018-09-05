package raknetserver.management;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

public class RakNetPacketReliability implements RakNetPacketReliabilityMXBean {

    public long msgQueued;
    public long msgToPacket;
    public long packetFlushed;
    public long fastReFlushed;
    public long ackReFlushed;
    public long rtoReFlushed;
    public long packetReFlushed;
    public long handlerActive;

    @Override
    public long getHandlerActive() {
        return handlerActive;
    }

    @Override
    public long getMsgQueued() {
        return msgQueued;
    }

    @Override
    public long getPacketFlushed() {
        return packetFlushed;
    }

    @Override
    public long getPacketReFlushed() {
        return packetReFlushed;
    }

    @Override
    public long getMsgToPacket() {
        return msgToPacket;
    }

    @Override
    public long getAckReFlushed() {
        return ackReFlushed;
    }

    @Override
    public long getRtoReFlushed() {
        return rtoReFlushed;
    }

    @Override
    public long getFastReFlushed() {
        return fastReFlushed;
    }

    @Override
    public void reset() {
        rtoReFlushed = fastReFlushed = ackReFlushed = msgToPacket = msgQueued = packetFlushed = packetReFlushed = handlerActive = 0;
    }

    @Override
    public ObjectName getObjectName() {
        try {
            return ObjectName.getInstance("raknetserver.management:type=RakNetPacketReliability");
        } catch (MalformedObjectNameException e) {
        }
        return null;
    }

}
