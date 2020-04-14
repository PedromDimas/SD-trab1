package sd1920.trab1.clients.utils;

import java.text.SimpleDateFormat;
import java.util.Date;

import sd1920.trab1.api.Message;

public class MessageUtills {

	public final static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	
	public static void printMessage(Message m) {
		System.out.println("From: " + m.getSender());
		System.out.print("To: "); 
		for(String s: m.getDestination()) 
			System.out.print(s + " ");
		System.out.println("");
		System.out.println("Date: " + dateFormat.format(new Date(m.getCreationTime())));
		System.out.println("Subject: " + m.getSubject());
		System.out.println("Contents: ");
		System.out.println(new String(m.getContents()));
	}
	
}
