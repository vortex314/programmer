package be.limero.programmer.commands;

import java.util.logging.Logger;

import be.limero.common.Bytes;
import be.limero.programmer.Stm32Model;

public class Stm32WriteProtect extends Stm32Msg {


	private static final Logger log = Logger.getLogger(Stm32GetVersionCommands.class.getName());

	static byte getCmdByte() {
		return (byte)0x63;
	}
	
	public Stm32WriteProtect() {
		super(100);
		cmd = Stm32Msg.CMD.STM32_CMD_BOOT_REQ.ordinal();
		messageId = Stm32Msg.getNextId();
		data.add(complementByte(getCmdByte()));
		acks.add(1);
		build();
	};

	public void handle(Stm32Model stm32) {
		parse();
		Bytes bytes;
		// ACK : 0:CMD ACK, 1:ADDRESS_ACK, 2:LENGTH:ACK, 3:DATA
		if ((bytes = data.get(0)) != null) {
			// first 3 bytes == ACKS
			//TODO update memoryImage
		}
		log.warning(" handle failed ");
	}
}
