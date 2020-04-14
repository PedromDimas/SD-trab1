package sd1920.trab1.api.soap;

import javax.xml.ws.WebFault;

@WebFault
public class MessagesException extends Exception {

	private static final long serialVersionUID = 1L;

	public MessagesException() {
		super("");
	}

	public MessagesException(String errorMessage ) {
		super(errorMessage);
	}
}
