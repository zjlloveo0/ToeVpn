package com.vcvnc.toevpn;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

public class MyServerSocket implements Runnable {
	private ServerSocket server = null;
	private ArrayList<MyConnect> myConnects = new ArrayList<MyConnect>();
	private boolean isRunning = true;
	private Thread thread;

	public MyServerSocket() {
		try {
			server = new ServerSocket(Config.PORT);
			thread = new Thread(this);
			thread.start();
		} catch (IOException e) {
			e.printStackTrace();;
		}
	}

	public void close() throws IOException {
		isRunning = false;
		server.close();
		for (MyConnect connect : myConnects) {
			connect.close();
		}
	}

	@Override
	public void run() {
		isRunning = true;
		System.out.println("server listen on: " + server.getLocalSocketAddress());
		while (isRunning) {
			try {
				Socket socket = server.accept();
				for (int i = 0; i < myConnects.size(); i++) {
					if (myConnects.get(i).isClose()) {
						myConnects.remove(i);
						i--;
					}
				}
				if (myConnects.size() < Config.MAX_CONNECT) {
					MyConnect connect = new MyConnect(socket);
					myConnects.add(connect);
				} else {
					socket.close();
					System.out.println("connect max");
				}
				//System.out.println("accept, total tcp socket: " + myConnects.size());

			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

}
