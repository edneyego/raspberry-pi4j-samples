package console;

import gnu.io.CommPortIdentifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Map;

import java.util.Set;


import console.util.DumpUtil;

import serial.io.SerialCommunicator;
import serial.io.SerialIOCallbacks;

public class SerialEchoClient implements SerialIOCallbacks {
	private static boolean verbose = "true".equals(System.getProperty("verbose", "false"));

	@Override
	public void connected(boolean b) {
		System.out.println("Serial port connected: " + b);
	}

	private int bufferIdx = 0;
	private final static int BUFFER_SIZE = 4096;
	private byte[] serialBuffer = new byte[BUFFER_SIZE];

	private void resetSerialBuffer() {
		for (int i = 0; i < serialBuffer.length; i++) {
			serialBuffer[i] = 0x0;
		}
	}

	@Override
	public void onSerialData(byte b) {
//  System.out.println("\t\tReceived character [0x" + Integer.toHexString(b & 0xFF) + "]");
		serialBuffer[bufferIdx++] = (byte) (b & 0xFF);
		if (b == 0xA) // \n , EOM
		{
			// Message completed
			byte[] mess = new byte[bufferIdx];
			System.arraycopy(serialBuffer, 0, mess, 0, bufferIdx);
			serialOutput(mess);
			// Reset
			bufferIdx = 0;
			resetSerialBuffer();
		}
	}

	@Override
	public void onSerialData(byte[] ba, int len) {
		if (this.verbose) {
			System.out.println("== onSerialData ==========================");
			System.out.println(String.format("%d: %s", len, DumpUtil.dumpHexMess(new String(ba, 0, len).getBytes())));
			System.out.println(String.format("Also [%s]", new String(ba, 0, len)));
		}
		System.arraycopy(ba, 0, serialBuffer, bufferIdx, len);
		bufferIdx += len;

		if (bufferIdx > 0) {
			String newMess = new String(serialBuffer, 0, bufferIdx);
			String[] messages = newMess.split("\n"); // Full lines end with \r\n
			if (this.verbose) {
				System.out.println(String.format("== onSerialData, just received %d bytes (now %d bytes), %d message(s):", len, newMess.length(), messages.length));
				System.out.println(DumpUtil.dumpHexMess(newMess.getBytes()));
				System.out.println("====================================");
			}
			Arrays.stream(messages).filter(str -> str.length() > 0 && str.charAt(0) != 0xD).forEach(mess -> {
				if (this.verbose) {
					System.out.println("\tMess len:" + mess.length());
					String[] sa = DumpUtil.dualDump(mess);
					if (sa != null) {
						Arrays.stream(sa).forEach(str -> System.out.println("\t" + str));
					}
				}
				serialOutput(mess);
			});
		}
		if (this.verbose) {
			System.out.println("...Reseting.");
		}
		bufferIdx = 0;
		resetSerialBuffer();
	}

	public void serialOutput(String mess) {
		serialOutput(mess.getBytes());
	}

	public void serialOutput(byte[] mess) {
		if (verbose) {
			try {
				String[] sa = DumpUtil.dualDump(mess);
				if (sa != null) {
//        System.out.println("\t>>> [From Serial port] Received:");
					for (String s : sa)
						System.out.println("\t\t" + s);
				} else {
					System.out.println(String.format("sa is null (mess %d byte(s)]...", mess.length));
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		int offset = 0;
		while (mess[offset] == 0xA || mess[offset] == 0xD) // Skip leading CR & NL
			offset++;
		String str = new String(mess, offset, mess.length - offset);
		System.out.print(str.replace('\r', '\n'));
		System.out.flush();
	}

	private static final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

	private static String userInput(String prompt) {
		String retString = "";
		if (prompt != null)
			System.err.print(prompt);
		try {
			retString = stdin.readLine();
		} catch (Exception e) {
			System.out.println(e);
			try {
				userInput("<Oooch/>");
			} catch (Exception exception) {
				exception.printStackTrace();
			}
		}
		return retString;
	}

	private static void displayHelp() {
		System.out.println("Special Commands:");
		System.out.println("=================");
		System.out.println("\\q[uit] to exit this shell (note: does not exit the session on the remote board)");
		System.out.println("[Return] to connect (to get the 'login' prompt)");
		System.out.println("\\tx [fileName] to transfer from host (this one) to remote (the board accessed serially)");
		System.out.println("\\h[elp] to display the help.");
	}

	private static void transfer(String pattern, SerialCommunicator sc) throws IOException {
		transfer(pattern, sc, null);
	}

	private static void transfer(String pattern, SerialCommunicator sc, String prefix) throws IOException {
		String fileName = (prefix != null ? prefix + File.separator : "") + pattern;
		System.out.println(String.format("Processing %s", fileName));

		File f = new File(fileName);
		if (f.isFile() && f.exists()) {
			System.out.println("Transferring " + fileName);
			// Remove first...
			sc.writeData("rm " + fileName.replace(File.separatorChar, '/') + " > /dev/null 2>&1 \n");

			FileReader fr = new FileReader(f);
			char[] buf = new char[256];
			int read = 0;
			while (read != -1) {
				read = fr.read(buf);
				if (read != -1) {
					for (int i = 0; i < read; i++) {
						String command = "echo -n -e \\\\x" + Integer.toHexString(buf[i]) + " >> " + fileName.replace(File.separatorChar, '/');
						sc.writeData(command + "\n");
					}
				}
			}
			fr.close();
		} else if (f.isDirectory() && f.exists()) {
			System.out.println("Directory " + fileName);

			sc.writeData("mkdir " + fileName.replace(File.separatorChar, '/') + " > /dev/null 2>&1 \n");
//    sc.writeData("cd " + pattern + "\n");
			String[] sub = f.list();
			Arrays.stream(sub).forEach(file -> {
				try {
					transfer(file, sc, fileName);
				} catch (IOException ioe) {
					ioe.printStackTrace();
				}
			});
		} else { // Pattern?
			System.out.println(String.format("Regex %s", pattern));
			// TODO Implement
		}
	}

	/**
	 * Can work with the RPi Serial Console (USB).
	 * <p>
	 * Pin #2  5V0 Red                             #1 . . #2   - Red
	 * Pin #6  Gnd Black                           #3 . . #4
	 * Pin #8  Tx  White                           #5 . . #6   - Black
	 * Pin #10 Rx  Green                           #7 . . #8   - White
	 *                                             #9 . . #10  - Green
	 * @param args                            etc #11 . . #12
	 */
	public static void main(String[] args) {
		final SerialEchoClient mwc = new SerialEchoClient();
		final SerialCommunicator sc = new SerialCommunicator(mwc);
		sc.setVerbose(verbose);

		Map<String, CommPortIdentifier> pm = sc.getPortList();
		Set<String> ports = pm.keySet();
		if (ports.size() == 0) {
			System.out.println("No serial port was found.");
			System.out.println("Did you run as administrator (sudo) ?");
		}
		System.out.println("== Serial Port List ==");
		for (String port : ports)
			System.out.println("-> " + port);
		System.out.println("======================");

		String serialPortName = System.getProperty("serial.port", "/dev/ttyUSB0");
		String baudRateStr = System.getProperty("baud.rate", "9600");
		System.out.println(String.format("Opening port %s:%s ...", serialPortName, baudRateStr));
		CommPortIdentifier serialPort = pm.get(serialPortName);
		if (serialPort == null) {
			System.out.println(String.format("Port %s not found, aborting", serialPortName));
			System.exit(1);
		}
		try {
			mwc.resetSerialBuffer();
			sc.connect(serialPort, "SerialRxTx", Integer.parseInt(baudRateStr));
			boolean b = sc.initIOStream();
			System.out.println("IO Streams " + (b ? "" : "NOT ") + "initialized");
			if (verbose) {
				System.out.println("Verbose: ON");
			}
			sc.initListener();

			System.out.println("==========================");
			displayHelp();
			System.out.println("==========================");

			System.out.println("Writing to the serial port.");

			boolean keepWorking = true;
			while (keepWorking) {
				String userInput = userInput(null);
				if (userInput.trim().equals("\\help") || (userInput.length() >= 2 && "\\help".startsWith(userInput.trim()))) // type just \h, \he ..., etc
				{
					displayHelp();
				} else if (userInput.trim().equals("\\quit") || (userInput.length() >= 2 && "\\quit".startsWith(userInput.trim()))) {
					System.out.println("Bye!");
					keepWorking = false;
				} else if (userInput.startsWith("\\tx ")) {
					String fileName = userInput.substring("\\tx ".length());
					transfer(fileName, sc);
				}
				// TODO \rx file, \cd dir, \dir
				else {
					sc.writeData(userInput + "\n");
//        System.out.println("Data written to the serial port.");
				}
			}
			System.out.println("Exiting program.");
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		System.out.println("Disconnecting...");
		if (sc.isConnected()) {
			try {
				sc.disconnect();
			} catch (IOException ioe) {
				ioe.printStackTrace();
			} catch (Exception ex) {
				ex.printStackTrace();
			} finally {
				System.out.println("Ooops!");
			}
		}
		System.out.println("Done.");
	}
}
