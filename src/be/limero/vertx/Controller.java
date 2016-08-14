package be.limero.vertx;

import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.logging.Logger;

import be.limero.file.FileManager;
import be.limero.programmer.Stm32Model;
import be.limero.programmer.Stm32Model.Verification;
import be.limero.programmer.ui.LogHandler;
import be.limero.programmer.ui.Stm32Programmer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;

public class Controller extends AbstractVerticle implements LogHandler.LogLine {
	private final static Logger log = Logger.getLogger(Controller.class.toString());
	static Vertx vertx = Vertx.vertx();
	private final static EventBus eb = vertx.eventBus();

	final int FLASH_START = 0x08000000;
	final int FLASH_SECTOR_SIZE = 256;
	final int FLASH_SIZE = 1024 * 128;

	Stm32Programmer ui;
	Stm32Model model;
	long fileCheckTimer;
	long fileLastModified;
	// MqttVerticle proxy;

	public Controller(Stm32Programmer ui) {
		try {
			this.ui = ui;
			this.model = ui.getStm32Model();
			// proxy = new MqttVerticle();
			LogHandler lh = new LogHandler();
			lh.register(this);

			eb.consumer("controller", message -> {
				onEbMessage(message.body());
				ui.updateView();
			});
			vertx.deployVerticle(this);
			vertx.deployVerticle(new UdpVerticle());
			fileCheckTimer = vertx.setPeriodic(1000, id -> {
				long fileTime = new File(model.getBinFile()).lastModified();
				if (model.isAutoProgram() & fileTime > fileLastModified) {
					eb.send("controller", "reset");
					eb.send("controller", "getId");
					eb.send("controller", "erase");
					eb.send("controller", "program");
					eb.send("controller", "go");
				}
				fileLastModified = fileTime;
			});
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void log(String line) {
		model.setLog(model.getLog() + "\n" + line);
		ui.updateView();

	}

	public void send(String s) {
		eb.send("controller", s);
	}
	// public void askDevice(5000,JsonObject req,
	// Handler<AsyncResult<Message<JsonObject>>> replyHandler) {

	static int nextId = 0;

	public void askDevice(int timeout, JsonObject req, Handler<JsonObject> replyHandler) {
		DeliveryOptions delOp = new DeliveryOptions();
		delOp.setSendTimeout(timeout);
		req.put("id", nextId++);
		eb.send("proxy", req, delOp, resp -> {
			// log.info(" handling " + req.getString("request") + " response");
			if (resp.succeeded()) {
				JsonObject json = (JsonObject) resp.result().body();
				int error = json.getInteger("error");
				if (error == 0) {
					replyHandler.handle(json);
				} else {
					log.info(" failed error : " + json.getInteger("error"));
				}
			} else if (resp.failed()) {
				log.info(" failed " + req.getString("request") + " " + resp.cause().getMessage());
			}
		});
	}

	public static String byteToHex(byte b) {
		int i = b & 0xFF;
		return Integer.toHexString(i);
	}

	public static String bytesToHex(byte[] bytes) {
		String result = new String();
		for (int i = 0; i < bytes.length; i++) {
			if (i != 0)
				result += " ";
			result += byteToHex(bytes[i]);
		}
		return result;

	}

	void onEbMessage(Object msg) {
		// log.info(" controller received :" + msg);
		if (msg instanceof JsonObject) {
			JsonObject json = (JsonObject) msg;
			if (json.containsKey("reply")) {
				String cmd = json.getString("reply");
				switch (cmd) {
				case "connect": {
					model.setConnected(json.getBoolean("connected"));
					break;
				}
				}
			} else if (json.containsKey("request")) {
				if (json.getString("request").equals("log")) {
					model.setLog(model.getLog() + json.getString("data"));
					ui.updateView();
				}
			}
		} else if (msg instanceof String) {
			String cmd = (String) msg;
			switch (cmd) {
			case "connect": {
				askDevice(5000, new JsonObject().put("request", "connect").put("host", model.getHost()).put("port",
						model.getPort()), reply -> {
							model.setConnected(reply.getBoolean("connected"));

						});
				break;
			}
			case "disconnect": {
				askDevice(5000, new JsonObject().put("request", "disconnect"), reply -> {
					model.setConnected(reply.getBoolean("connected"));

				});

				break;
			}

			case "erase": {
				byte[] cmds = model.getCommands();
				for (int i = 0; i < cmds.length; i++) {
					if (cmds[i] == 0x43)
						askDevice(5000, new JsonObject().put("request", "eraseAll"), reply -> {
						});
					else if (cmds[i] == 0x44)
						askDevice(5000, new JsonObject().put("request", "extendedEraseMemory"), reply -> {
						});
				}
				break;
			}

			case "get": {
				askDevice(5000, new JsonObject().put("request", "get"), reply -> {
					log.info(" cmds :" + bytesToHex(reply.getBinary("cmds")));
					model.setCommands(reply.getBinary("cmds"));
				});
				break;
			}
			case "getId": {
				askDevice(5000, new JsonObject().put("request", "getId"), reply -> {
					log.info(" chipId :" + Integer.toHexString(reply.getInteger("chipId")));
				});
				break;
			}
			case "getVersion": {
				askDevice(5000, new JsonObject().put("request", "getId"), reply -> {
					// log.info(" reply " + reply);
				});
				break;
			}

			case "program": {
				model.setFileMemory(FileManager.loadBinaryFile(model.getBinFile()));
				log.info(" binary image size : " + model.getFileMemory().length);
				int offset = 0;
				int fileLength = model.getFileMemory().length;
				while (true) {
					final int sectorLength = (offset + FLASH_SECTOR_SIZE) < fileLength ? FLASH_SECTOR_SIZE
							: model.getFileMemory().length - offset;
					// log.info(" length :" + length + " offset : " + offset);
					if (sectorLength == 0)
						break;
					byte[] sector = Arrays.copyOfRange(model.getFileMemory(), offset, offset + sectorLength);
					askDevice(50000, new JsonObject().put("request", "writeMemory").put("address", FLASH_START + offset)
							.put("length", sectorLength).put("data", sector), reply -> {
								int percentage = ((reply.getInteger("address") + FLASH_SECTOR_SIZE - FLASH_START) * 100)
										/ fileLength;
								// log.info(" percentage :" + percentage + "
								// address : "
								// +
								// Integer.toHexString(reply.getInteger("address"))
								// + " length : " + fileLength);
								model.setProgress(percentage);
								ui.updateView();
								// log.info(" reply " + reply);
							});
					offset += FLASH_SECTOR_SIZE;
					if (offset > fileLength)
						break;
				}
				break;
			}
			case "read": {
				for (int i = 0; i < FLASH_SIZE; i += FLASH_SECTOR_SIZE) {
					askDevice(50000, new JsonObject().put("request", "readMemory").put("address", FLASH_START + i)
							.put("length", FLASH_SECTOR_SIZE), reply -> {
								// log.info(" reply " + reply);
								int percentage = ((reply.getInteger("address") + FLASH_SECTOR_SIZE - FLASH_START) * 100)
										/ FLASH_SIZE;
								// log.info(" percentage :" + percentage + "
								// address : "
								// +
								// Integer.toHexString(reply.getInteger("address"))
								// + " length : " + FLASH_SIZE);
								model.setProgress(percentage);
								ui.updateView();

							});
				}
				break;
			}

			case "verify": {
				model.setFileMemory(FileManager.loadBinaryFile(model.getBinFile()));
				log.info(" binary image size : " + model.getFileMemory().length);
				int binLength = model.getFileMemory().length;

				int offset = 0;
				model.setVerification(Verification.OK);
				while (true) {
					final int sectorLength = (offset + FLASH_SECTOR_SIZE) < binLength ? FLASH_SECTOR_SIZE
							: binLength - offset;
					askDevice(50000, new JsonObject().put("request", "readMemory").put("address", FLASH_START + offset)
							.put("length", sectorLength), reply -> {

								int address = reply.getInteger("address");
								byte flashSector[] = reply.getBinary("data");

								int percentage = ((address + FLASH_SECTOR_SIZE - FLASH_START) * 100) / binLength;
								model.setProgress(percentage);
								ui.updateView();

								int off = address - FLASH_START;
								byte binSector[] = Arrays.copyOfRange(model.getFileMemory(), off,
										off + flashSector.length);

								// log.info(" percentage :" + percentage + "
								// address : " + Integer.toHexString(address)
								// + " length : " + binLength + " flash : " +
								// flashSector.length + " bin : "
								// + binSector.length);
								for (int j = 0; j < flashSector.length; j++) {
									if (flashSector[j] != binSector[j]) {
										log.info(" flash differs at 0x" + Integer.toHexString(j + off + FLASH_START)
												+ " flash : 0x" + byteToHex(flashSector[j]) + " bin : 0x"
												+ byteToHex(binSector[j]));
										model.setVerification(Verification.FAIL);
										break;
									}
								}

							});
					offset += FLASH_SECTOR_SIZE;
					if (offset > binLength)
						break;
				}
				break;
			}

			case "status": {
				askDevice(5000, new JsonObject().put("request", "status"), reply -> {
					// log.info(" reply " + reply);
				});
				break;
			}

			case "reset": {
				askDevice(5000, new JsonObject().put("request", "reset"), reply -> {
					// log.info(" reply " + reply);
				});
				break;
			}
			case "go": {
				askDevice(5000, new JsonObject().put("request", "go").put("address", FLASH_START), reply -> {
					// log.info(" reply " + reply);
				});
				break;
			}
			case "baudrate": {
				askDevice(5000, new JsonObject().put("request", "settings").put("baudrate", 460800), reply -> {
					// log.info(" reply " + reply);
				});
				break;
			}
			}
		}
	}

	@Override
	public void start(Future<Void> startFuture) {
		log.info("ControllerVerticle started!");
	}

	@Override
	public void stop(Future<Void> stopFuture) throws Exception {
		log.info("ControllerVerticle stopped!");
	}

}
