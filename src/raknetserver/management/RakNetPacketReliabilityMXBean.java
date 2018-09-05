package raknetserver.management;

import java.lang.management.PlatformManagedObject;

public interface RakNetPacketReliabilityMXBean extends PlatformManagedObject {

    long getHandlerActive();

    long getMsgQueued();

    long getPacketFlushed();

    long getPacketReFlushed();

    long getMsgToPacket();

    long getAckReFlushed();

    long getRtoReFlushed();

    long getFastReFlushed();

    void reset();

}
