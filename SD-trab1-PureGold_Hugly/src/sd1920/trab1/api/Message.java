package sd1920.trab1.api;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents a message in the system.
 */
public class Message {

	private long id;
	private String sender;
	private Set<String> destination;
	private long creationTime;
	private String subject;
	private byte[] contents;
	
	public Message() {
		this.id = -1;
		this.sender = null;
		this.destination = new HashSet<String>();
		this.creationTime = System.currentTimeMillis();
		this.subject = null;
		this.contents = null;
	}
	
	public Message(String sender, String destination, String subject, byte[] contents) {
		this.id = -1;
		this.sender = sender;
		this.destination = new HashSet<String>();
		this.destination.add(destination);
		this.creationTime = System.currentTimeMillis();
		this.subject = subject;
		this.contents = contents;
	}
	
	public Message(String sender, Set<String> destinations, String subject, byte[] contents) {
		this.id = -1;
		this.sender = sender;
		this.destination = new HashSet<String>();
		this.destination.addAll(destinations);
		this.creationTime = System.currentTimeMillis();
		this.subject = subject;
		this.contents = contents;
	}

	public Message(long id, String sender, Set<String> destinations, String subject, byte[] contents) {
		this.id = id;
		this.sender = sender;
		this.destination = new HashSet<String>();
		this.destination.addAll(destinations);
		this.creationTime = System.currentTimeMillis();
		this.subject = subject;
		this.contents = contents;
	}

	public String getSender() {
		return sender;
	}
	
	public void setSender(String sender) {
		this.sender = sender;
	}
	
	public Set<String> getDestination() {
		return destination;
	}
	
	public void setDestination(Set<String> destination) {
		this.destination = destination;
	}
	
	public void addDestination(String destination) {
		this.destination.add(destination);
	}

	public long getCreationTime() {
		return creationTime;
	}

	public void setCreationTime(long creationTime) {
		this.creationTime = creationTime;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public byte[] getContents() {
		return contents;
	}

	public void setContents(byte[] contents) {
		this.contents = contents;
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}
}
