package com.vcvnc.toevpn;

public class ToeVpn {
	public static void main(String args[]) {
		if (args.length > 0) {
			Config.PORT = Integer.parseInt(args[0]);
		}
		new MyServerSocket();
	}
}
