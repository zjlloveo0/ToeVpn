package com.vcvnc.toevpn;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpProxy implements Runnable {
	private MyConnect connect;
	public Packet fistPacket;
	private InetAddress ip;
	private int port;
	private AtomicLong myDataIndex = new AtomicLong();
	private AtomicLong clientDataIndex = new AtomicLong();
	private AtomicInteger identification = new AtomicInteger();
	// private long clientWindowSize;
	// private long myWindowSize;
	private boolean SYN_RCVD = false;
	private boolean ESTABLISHED = false;
	private boolean CLOSE_WAIT = false;
	private boolean LAST_ACK = false;
	private Socket socket;
	private InputStream is;
	private OutputStream os;
	private CopyOnWriteArrayList<byte[]> waitSendData = new CopyOnWriteArrayList<byte[]>();

	private boolean init = false;
	public boolean isClose = false;
	private Thread thread;
	public long lastRefreshTime;

	public TcpProxy(MyConnect connect, Packet packet) {
		this.connect = connect;
		this.ip = packet.getIp4Header().destinationAddress;
		this.port = packet.getTcpHeader().destinationPort;
		this.myDataIndex.set(10086);
		this.clientDataIndex.set(packet.getTcpHeader().sequenceNumber);
		this.SYN_RCVD = true;

		packet.getTcpHeader().headerLength = 20;
		packet.getTcpHeader().window = 65535;
		packet.updateTCPBuffer(packet.backingBuffer, (byte) 0, 0, 0, 0);
		this.fistPacket = packet;

		Thread thread = new Thread(() -> {
			init();
			while (!this.isClose()) {
				for (int i = 0; i < waitSendData.size(); i++) {
					if(isClose)break;
					this.writeToServer(waitSendData.get(i), 0, waitSendData.get(i).length);
					this.waitSendData.remove(i);
					i--;
				}
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		});
		thread.start();
	}

	public void init() {
		try {
			socket = new Socket(ip, port);
			is = socket.getInputStream();
			os = socket.getOutputStream();
			init = true;
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			System.out.println("bad address:" + ip + ":" + port);
			// e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.out.println("Connect error: " + this.ip + ":" + this.port);
			// e.printStackTrace();
		}
		if (init) {
			thread = new Thread(this);
			thread.start();
		} else {
			close();
		}
	}

	public void writeToSendBuf(byte[] packet, int offset, int size) {
		// System.out.println("writeToSendBuf "+ size);
		lastRefreshTime = System.currentTimeMillis();
		byte[] data = new byte[size];
		System.arraycopy(packet, offset, data, 0, size);
		this.waitSendData.add(data);
	}

	public void writeToServer(byte[] packet, int offset, int size) {
		// printfPacket(packet, offset, size);
		try {
			os.write(packet, offset, size);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			close();
			e.printStackTrace();
		}
	}

	public void close() {
		isClose = true;
		if (thread != null)
			thread.interrupt();
		try {
			if (socket != null) {
				socket.close();
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public boolean isClose() {
		if (socket == null)
			return true;
		return socket.isClosed() || isClose;
	}

	public void printfPacket(byte[] packet, int offset, int size) {
		System.out.println(ip + ":" + port + " size:" + size + "\r\n");
		for (int i = offset; i < size + offset; i++) {
			System.out.printf("0x%s ", Integer.toHexString(packet[i]));
		}
	}

	private int getIdentificationAndFlagsAndFragmentOffset() {
		return identification.getAndIncrement() << 16;
	}

	public void processSYNPacket(Packet packet) throws UnknownHostException {
		// 创建SYN回复帧
		byte[] data = new byte[packet.backingBuffer.array().length];
		System.arraycopy(packet.backingBuffer.array(), 0, data, 0, packet.backingBuffer.array().length);
		ByteBuffer byteBuffer = ByteBuffer.wrap(data, 0, packet.backingBuffer.array().length);
		Packet backPacket = new Packet(byteBuffer);

		backPacket.swapSourceAndDestination();
		backPacket
				.getIp4Header().identificationAndFlagsAndFragmentOffset = getIdentificationAndFlagsAndFragmentOffset();
		packet.getTcpHeader().headerLength = 20;
		backPacket.getIp4Header().totalLength = TcpTunnel.HEADER_SIZE;
		backPacket.getTcpHeader().window = 65535;

		byte flags = (byte) (Packet.TCPHeader.SYN | Packet.TCPHeader.ACK);

		long seq = this.myDataIndex.get();
		long ack = this.clientDataIndex.get() + 1;
		backPacket.updateTCPBuffer(backPacket.backingBuffer, flags, seq, ack, 0);

		// 往vpn客户端写SYN ACK数据帧
		// System.out.println("往vpn客户端写SYN ACK回复帧");

		connect.sendToClient(backPacket.backingBuffer.array(), 0, TcpTunnel.HEADER_SIZE);
		SYN_RCVD = true;
		ESTABLISHED = false;
		CLOSE_WAIT = false;
		LAST_ACK = false;

	}

	public void processACKPacket(Packet packet) {
		if (SYN_RCVD) {
			if (packet.getTcpHeader().sequenceNumber == (this.clientDataIndex.get() + 1)) {
				ESTABLISHED = true;
				SYN_RCVD = false;
				// System.out.println("ESTABLISHED = true");
			}
		} else if (this.ESTABLISHED) {
			processESTABLISHEDACKPacket(packet);
		} else if (this.CLOSE_WAIT) {
			System.out.println("CLOSE_WAIT ack packet");
		} else if (this.LAST_ACK) {
			processLASTACKPacket(packet);
		} else {
			System.out.println("unkown ack packet");
		}
	}

	public void processESTABLISHEDACKPacket(Packet packet) {
		if (packet.getTcpHeader().sequenceNumber == (this.clientDataIndex.get() + 1)) {
			// System.out.println("序列号匹配");
			int headerLen = Packet.IP4_HEADER_SIZE + packet.getTcpHeader().headerLength;
			int dataSize = packet.getIp4Header().totalLength - headerLen;
			// 下一个序列号
			this.clientDataIndex.addAndGet(dataSize);

			if (dataSize > 0)
				this.writeToSendBuf(packet.backingBuffer.array(), headerLen, dataSize);

			// 确认收到该包
			
			packet.swapSourceAndDestination();
			packet.getTcpHeader().headerLength = 20;
			packet.ip4Header.totalLength = TcpTunnel.HEADER_SIZE;
			packet.getIp4Header().identificationAndFlagsAndFragmentOffset = getIdentificationAndFlagsAndFragmentOffset();
			byte flags = (byte) Packet.TCPHeader.ACK;
			// 计算序列号
			long seq = this.myDataIndex.get() + 1;
			// 计算确认号
			long ack = this.clientDataIndex.get() + 1;
			packet.updateTCPBuffer(packet.backingBuffer, flags, seq, ack, 0);
			// 往vpn客户端写ACK回复帧
			connect.sendToClient(packet.backingBuffer.array(), 0, TcpTunnel.HEADER_SIZE);
			
			// System.out.println("下一个客户端包index: " + this.clientDataIndex);

		} else if (packet.getTcpHeader().sequenceNumber <= (this.clientDataIndex.get() + 1)) {
			int dataSize = packet.getIp4Header().totalLength - TcpTunnel.HEADER_SIZE;
			if ((packet.getTcpHeader().sequenceNumber + dataSize - 1) > this.clientDataIndex.get()) {
				System.out.println("未知错误,无法处理包，seq+datasize大于clientDataIndex");
			} else {
				// System.out.println("数据重传不处理");
				// 确认收到该包
				packet.swapSourceAndDestination();
				packet.getTcpHeader().headerLength = 20;
				packet.ip4Header.totalLength = TcpTunnel.HEADER_SIZE;
				packet.getIp4Header().identificationAndFlagsAndFragmentOffset = getIdentificationAndFlagsAndFragmentOffset();
				byte flags = (byte) Packet.TCPHeader.ACK;
				// 计算序列号
				long seq = this.myDataIndex.get() + 1;
				// 计算确认号
				long ack = this.clientDataIndex.get() + 1;
				packet.updateTCPBuffer(packet.backingBuffer, flags, seq, ack, 0);
				// 往vpn客户端写ACK回复帧
				connect.sendToClient(packet.backingBuffer.array(), 0, TcpTunnel.HEADER_SIZE);
				
			}
		} else {

			System.out.print(packet.getTcpHeader().sourcePort + "seq不匹配，原因：大于");
			System.out.println(" 收到seq:" + packet.getTcpHeader().sequenceNumber + " 需要seq:"
					+ (this.clientDataIndex.get() + 1));
			/*
			// 让client重发数据
			packet.swapSourceAndDestination();
			packet.getTcpHeader().headerLength = 20;
			packet.ip4Header.totalLength = TcpTunnel.HEADER_SIZE;
			packet.getIp4Header().identificationAndFlagsAndFragmentOffset = getIdentificationAndFlagsAndFragmentOffset();
			byte flags = (byte) Packet.TCPHeader.ACK;
			// 计算序列号
			long seq = this.myDataIndex.get() + 1;
			// 计算确认号
			long ack = this.clientDataIndex.get() + 1;
			packet.updateTCPBuffer(packet.backingBuffer, flags, seq, ack, 0);
			// 往vpn客户端写ACK回复帧
			connect.sendToClient(packet.backingBuffer.array(), 0, TcpTunnel.HEADER_SIZE);
			*/
			close();
		}
	}

	public void processFINPacket(Packet packet) {
		// 创建ACK回复帧
		packet.swapSourceAndDestination();
		packet.getTcpHeader().headerLength = 20;
		packet.ip4Header.totalLength = TcpTunnel.HEADER_SIZE;
		packet.getIp4Header().identificationAndFlagsAndFragmentOffset = getIdentificationAndFlagsAndFragmentOffset();
		byte flags = (byte) (Packet.TCPHeader.ACK);

		long seq = this.myDataIndex.get() + 1;
		long ack = this.clientDataIndex.get() + 1;
		packet.updateTCPBuffer(packet.backingBuffer, flags, seq, ack, 0);

		// 往vpn客户端写ACK回复帧
		connect.sendToClient(packet.backingBuffer.array(), 0, TcpTunnel.HEADER_SIZE);
		SYN_RCVD = false;
		ESTABLISHED = false;
		CLOSE_WAIT = true;
		LAST_ACK = false;

		// 创建FIN ACK 回复帧
		byte flags2 = (byte) (Packet.TCPHeader.FIN | Packet.TCPHeader.ACK);

		packet.updateTCPBuffer(packet.backingBuffer, flags2, seq, ack, 0);

		// 往vpn客户端写FIN ACK回复帧
		connect.sendToClient(packet.backingBuffer.array(), 0, TcpTunnel.HEADER_SIZE);
		SYN_RCVD = false;
		ESTABLISHED = false;
		CLOSE_WAIT = false;
		LAST_ACK = true;
	}

	public void processLASTACKPacket(Packet packet) {
		if (packet.getTcpHeader().sequenceNumber == (this.clientDataIndex.get() + 2)) {
			if (packet.getTcpHeader().acknowledgementNumber == (this.myDataIndex.get() + 2)) { // ack == send seq +1;
				// System.out.println("ack 匹配 关闭连接");
				close();
			} else {
				// 数据还未送达
				//System.out.println("ack: " + packet.getTcpHeader().acknowledgementNumber + "不匹配 my data index:"+ this.myDataIndex);
				close();
			}
		} else if(packet.getTcpHeader().sequenceNumber > (this.clientDataIndex.get() + 2)){
			close();
		} else {
			//System.out.println( packet.getTcpHeader().sequenceNumber + "不是last seq 我的clientdataindex:" + this.clientDataIndex);
			close();
		}
	}

	@Override
	public void run() {
		try {
			int size = 0;
			while (size != -1 && !isClose()) {
				byte[] bytes = new byte[Config.MUTE];
				size = is.read(bytes, TcpTunnel.HEADER_SIZE, Config.MUTE - TcpTunnel.HEADER_SIZE);

				if (size > 0) {
					System.arraycopy(this.fistPacket.backingBuffer.array(), 0, bytes, 0, TcpTunnel.HEADER_SIZE);
					ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, 0, size + TcpTunnel.HEADER_SIZE);
					Packet packet = new Packet(byteBuffer);

					packet.getIp4Header().totalLength = size + TcpTunnel.HEADER_SIZE;
					packet.getIp4Header().identificationAndFlagsAndFragmentOffset = getIdentificationAndFlagsAndFragmentOffset();
					packet.swapSourceAndDestination();
					byte flag = Packet.TCPHeader.ACK;
					long seq = this.myDataIndex.get() + 1;
					long ack = this.clientDataIndex.get() + 1;
					packet.updateTCPBuffer(packet.backingBuffer, flag, seq, ack, size);
					// 往vpn客户端写ACK数据帧
					// System.out.println(packet.getTcpHeader().destinationPort + "往vpn客户端写ACK数据帧
					// data size: "+ size+" mydataindex :" + this.myDataIndex);
					connect.sendToClient(packet.backingBuffer.array(), 0, size + TcpTunnel.HEADER_SIZE);
					this.myDataIndex.addAndGet(size);

				}
				lastRefreshTime = System.currentTimeMillis();
			}

		} catch (IOException e) {
			// TODO Auto-generated catch block
			// e.printStackTrace();
		} finally {
			close();
		}
	}
}
