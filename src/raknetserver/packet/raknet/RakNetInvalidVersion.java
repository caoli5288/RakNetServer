package raknetserver.packet.raknet;

import io.netty.buffer.ByteBuf;
import raknetserver.packet.RakNetConstants;

public class RakNetInvalidVersion implements RakNetPacket {

	public static final int VERSION_8 = 8;
	public static final int VERSION_9 = 9;

	@Override
	public void decode(ByteBuf buf) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void encode(ByteBuf buf) {
		buf.writeByte(VERSION_8);
		buf.writeBytes(RakNetConstants.MAGIC);
		buf.writeLong(RakNetConstants.SERVER_ID);
	}

	public static boolean validate(int version) {
		return VERSION_8 <= version && version <= VERSION_9;
	}

}
