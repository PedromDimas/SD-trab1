package sd1920.trab1.api;

/**
 * Represents a user in the system.
 */
public class User {
	private String name;
	private String pwd;
	private String displayName;
	private String domain;
	
	public User() {
		this.name = null;
		this.pwd = null;
		this.displayName = null;
		this.domain = null;
	}
	public User(String name, String pwd, String domain) {
		this.name = name;
		this.pwd = pwd;
		this.displayName = null;
		this.domain = domain;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getPwd() {
		return pwd;
	}
	public void setPwd(String pwd) {
		this.pwd = pwd;
	}
	public String getDisplayName() {
		return displayName;
	}
	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}
	public String getDomain() {
		return domain;
	}
	public void setDomain(String domain) {
		this.domain = domain;
	}
}
