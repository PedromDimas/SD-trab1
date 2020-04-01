package sd1920.trab1.api.rest;

import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import sd1920.trab1.api.Message;

@Path(MessageService.PATH)
public interface MessageService {
	String PATH = "/messages";
	
	/**
	 * Posts a new message to the server, associating it to the inbox of every individual destination.
	 * An outgoing message should be modified before delivering it, by assigning an ID, and by changing the
	 * sender to be in the format "display name <name@domain>", with display name the display name
	 * associated with a user.
	 * NOTE: there might be some destinations that are not from the local domain (see grading for 
	 * how addressing this feature is valued).
	 * 
	 * @param msg the message object to be posted to the server
	 * @param pwd password of the user sending the message
	 * @return 200 the unique numerical identifier for the posted message;
	 * 403 if the sender does not exist or if the pwd is not correct (NOTE: sender can be in the form 
	 * "name" or "name@domain");
	 * 409 otherwise
	 */
	@POST
	@Path("/")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	long postMessage(@QueryParam("pwd") String pwd, Message msg);
	
	/**
	 * Obtains the message identified by mid of user user
	 * @param user user name for the operation
	 * @param mid the identifier of the message
	 * @param pwd password of the user
	 * @return 200 the message if it exists;
	 *  403 if the user does not exist or if the pwd is not correct;
	 *  404 if the message does not exists
	 */
	@GET
	@Path("/mbox/{user}/{mid}")
	@Produces(MediaType.APPLICATION_JSON)
	Message getMessage(@PathParam("user") String user, @PathParam("mid") long mid,
			@QueryParam("pwd") String pwd);

	/**
	 * Returns a list of all ids of messages stored in the server for a given user
	 * @param user the username of the user whose message ids should be returned
	 * @param pwd password of the user
	 * @return 200 a list of ids potentially empty;
	 *  403 if the user does not exist or if the pwd is not correct.
	 */
	@GET
	@Path("/mbox/{user}")
	@Produces(MediaType.APPLICATION_JSON)
	List<Long> getMessages(@PathParam("user") String user, @QueryParam("pwd") String pwd);

	/**
	 * Removes a message identified by mid from the inbox of user identified by user.
	 * @param user the username of the inbox that is manipulated by this method
	 * @param mid the identifier of the message to be deleted
	 * @param pwd password of the user
	 * @return 204 if ok
	 * 403 if the user does not exist or if the pwd is not correct;
	 * 404 is generated if the message does not exist in the server.
	 */
	@DELETE
	@Path("/mbox/{user}/{mid}")
	void removeFromUserInbox(@PathParam("user") String user, @PathParam("mid") long mid, 
			@QueryParam("pwd") String pwd);

	/**
	 * Removes the message identified by mid from the inboxes of any server that holds the message.
	 * The deletion can be executed asynchronously and does not generate any error message if the
	 * message does not exist.
	 * 
	 * @param user the username of the sender of the message to be deleted
	 * @param mid the identifier of the message to be deleted
	 * @param pwd password of the user that sent the message
	 * @return 204 if ok
	 * 403 is generated if the user does not exist or if the pwd is not correct
	 */
	@DELETE
	@Path("/msg/{user}/{mid}")
	@Produces(MediaType.APPLICATION_JSON)
	void deleteMessage(@PathParam("user") String user, @PathParam("mid") long mid, 
			@QueryParam("pwd") String pwd);
	
	
}
