package sd1920.trab1.api.rest;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import sd1920.trab1.api.User;

@Path(UserService.PATH)
public interface UserService {

	String PATH = "/users";
	
	/**
	 * Creates a new user in the local domain.
	 * @param user User to be created
	 * @return 200 the address of the user (name@domain). 
	 * 			403 if the domain in the user does not match the domain of the server 
	 * 			409 otherwise
	 */
	@POST
	@Path("/")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	String postUser(User user);
	
	/**
	 * Obtains the information on the user identified by name
	 * @param name the name of the user
	 * @param pwd password of the user
	 * @return 200 the user object, if the name exists and pwd matches the existing
	 *         password
	 *         403 if the password is incorrect or the user does not exist 
	 *         409 otherwise
	 */
	@GET
	@Path("/{name}")
	@Produces(MediaType.APPLICATION_JSON)
	User getUser(@PathParam("name") String name, @QueryParam("pwd") String pwd);
	
	/**
	 * Modifies the information of a user. Values of null in any field of the user will be 
	 * considered as if the the fields is not to be modified (the name cannot be modified).
	 * @param name the name of the user
	 * @param pwd password of the user
	 * @param user Updated information
	 * @return 200 the updated user object, if the name exists and pwd matches the
	 *         existing password 
	 *         403 if the password is incorrect or the user does not exist 
	 *         409 otherwise
	 */
	@PUT
	@Path("/{name}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	User updateUser(@PathParam("name") String name, @QueryParam("pwd") String pwd, User user);
	
	/**
	 * Deletes the user identified by name
	 * @param name the name of the user
	 * @param pwd password of the user
	 * @return 200 the deleted user object, if the name exists and pwd matches the
	 *         existing password 
	 *         403 if the password is incorrect or the user does not exist 
	 *         409 otherwise
	 */
	@DELETE
	@Path("/{name}")
	@Produces(MediaType.APPLICATION_JSON)
	User deleteUser(@PathParam("name") String name, @QueryParam("pwd") String pwd);
	
}
