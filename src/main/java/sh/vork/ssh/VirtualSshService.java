package sh.vork.ssh;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.sshtools.client.KeyPairAuthenticator;
import com.sshtools.client.PasswordAuthenticator;
import com.sshtools.client.SshClient;
import com.sshtools.client.SshClient.SshClientBuilder;
import com.sshtools.client.SshClientContext;
import com.sshtools.common.files.AbstractFileFactory;
import com.sshtools.common.files.memory.InMemoryFileFactory;
import com.sshtools.common.knownhosts.HostKeyVerification;
import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.common.policy.FileFactory;
import com.sshtools.common.policy.FileSystemPolicy.FileSystemPolicyBuilder;
import com.sshtools.common.publickey.InvalidPassphraseException;
import com.sshtools.common.publickey.SshKeyPairGenerator;
import com.sshtools.common.publickey.SshKeyUtils;
import com.sshtools.common.ssh.SshConnection;
import com.sshtools.common.ssh.SshException;
import com.sshtools.common.ssh.components.SshKeyPair;
import com.sshtools.common.ssh.components.SshPublicKey;
import com.sshtools.server.AbstractSshServer;
import com.sshtools.server.DefaultServerChannelFactory;
import com.sshtools.server.InMemoryPublicKeyAuthenticator;
import com.sshtools.server.SshServerContext;
import com.sshtools.server.vsession.commands.os.NativeSessionChannel;
import com.sshtools.synergy.nio.ConnectRequestFuture;
import com.sshtools.synergy.nio.SshEngineContext;

import io.micrometer.common.util.StringUtils;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.SearchQuery;
import sh.vork.database.SortOrder;
import sh.vork.security.SecureCredentialStore;
import sh.vork.security.VorkUser;

@Service
public class VirtualSshService extends AbstractSshServer {

	static Logger log = LoggerFactory.getLogger(VirtualSshService.class.getName());
	SshKeyPair authenticationKeyPair;
	SshKeyPair hostKeyPair;

	@Autowired
	private SecureCredentialStore credentialStore;

	@Autowired
	private DatabaseRepository<VorkNode> nodeRepository;
	
	@PostConstruct
	private void init() throws IOException, SshException {
		authenticationKeyPair = SshKeyPairGenerator.generateKeyPair(SshKeyPairGenerator.ED25519);
		hostKeyPair = SshKeyPairGenerator.generateKeyPair(SshKeyPairGenerator.ED25519);
		start(false);
	}

	@Override
	public SshServerContext createServerContext(SshEngineContext daemonContext, SocketChannel sc)
			throws IOException, SshException {
		
		SshServerContext context = new SshServerContext(daemonContext.getEngine());
		context.addHostKey(hostKeyPair);
		context.getAuthenticationMechanismFactory()
			.addProvider(new InMemoryPublicKeyAuthenticator()
					.addAuthorizedKey("system", 
							authenticationKeyPair.getPublicKey()));
		
		context.setPolicy(FileSystemPolicyBuilder.create().withFileFactory(new FileFactory() {
			@Override
			public AbstractFileFactory<?> getFileFactory(SshConnection con)
					throws IOException, PermissionDeniedException {
				return new InMemoryFileFactory();
			}
			
		}).build());
		
		context.setChannelFactory(new DefaultServerChannelFactory() {
			@Override
			public NativeSessionChannel createSessionChannel(SshConnection con) {
				return new NativeSessionChannel(con);
			}
		});
		
		// context.setChannelFactory(new VirtualChannelFactory(new CommandProvider<ShellCommand>() {
		// 	@Override
		// 	public ShellCommand createCommand(String command, SshConnection con) throws UnsupportedCommandException {

		// 		VirtualCommand inst = appContext.getBeansOfType(VirtualCommandFactory.class)
		// 	            .values()
		// 	            .stream()
		// 	            .filter(factory -> factory.getName().equals(command))
		// 	            .findFirst()
		// 	            .orElseThrow(() -> new UnsupportedCommandException(command))
		// 	            .createCommand(command, con);

	    //         appContext.getAutowireCapableBeanFactory().autowireBean(inst);
	            
	    //         return inst;
		// 	}

		// 	@Override
		// 	public Set<String> getSupportedCommands() {
		// 		return appContext.getBeansOfType(VirtualCommandFactory.class)
		// 	            .values()
		// 	            .stream()
		// 	            .map(VirtualCommandFactory::getName) // Inherited from ShellCommand
		// 	            .collect(Collectors.toSet());
		// 	}

		// 	@Override
		// 	public boolean supportsCommand(String command) {
		// 		return getSupportedCommands().contains(command);
		// 	}
		// }));
		return context;
	}
	
	
	public SshClient connectLocal(int timeout) throws IOException, SshException, InterruptedException {
		
		SshClientContext context = new SshClientContext();
		context.setUsername("system");
		context.addAuthenticator(new KeyPairAuthenticator(authenticationKeyPair));
        ConnectRequestFuture future = acceptVirtualConnection(context);
        future.waitFor(Duration.ofSeconds(timeout));
        SshConnection con = future.getConnection();
        con.getAuthenticatedFuture().waitFor(Duration.ofSeconds(timeout));

        return SshClientBuilder.create(con).build();
	}

	public SshClient connectClient(String username, String host, int timeout) throws IOException, SshException, InterruptedException {

		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("SSH host is required");
		}

		VorkUser principal = currentPrincipalUser();
		String normalizedHost = normalizeHost(host);
		int port = extractPort(normalizedHost);
		VorkNode node = resolveNode(principal, normalizedHost, port, username);
		String normalizedUser = node.username();

		String passwordSecretKey = secretKeyForPassword(node.uuid());
		String keySecretKey = secretKeyForPrivateKey(node.uuid());
		String passphraseSecretKey = secretKeyForPassphrase(node.uuid());

		SshClientContext context = new SshClientContext();
		context.setUsername(normalizedUser);
		
		String password = credentialStore.getSecret(principal, passwordSecretKey);	
		if(password != null) {
			 context.addAuthenticator(PasswordAuthenticator.forPassword(password));
		}

		String key = credentialStore.getSecret(principal, keySecretKey);
		String passphrase = credentialStore.getSecret(principal, passphraseSecretKey);
		boolean hasUsableAuth = password != null && !password.isBlank();

		if(key != null && !key.isBlank()) {
			try {
				SshKeyPair keyPair = SshKeyUtils.getPrivateKey(key, passphrase);
				context.addAuthenticator(new KeyPairAuthenticator(keyPair));
				hasUsableAuth = true;
			} catch (IOException e) { 
				if (!hasUsableAuth) {
					throw credentialsPrompt(node,
							"The stored private key is invalid. Provide a valid SSH private key or password.",
							false);
				}
			} catch(InvalidPassphraseException e) {
				if (!hasUsableAuth) {
					throw credentialsPrompt(node,
							"The private key requires a valid passphrase. Provide the key passphrase (or password instead).",
							true);
				}
			}
		}

		if(!hasUsableAuth || context.getAuthenticators().isEmpty()) {
			throw credentialsPrompt(node,
					"No stored SSH credentials were found for this user/host. Provide a password and/or private key.",
					false);
		}

		context.setHostKeyVerification(new HostKeyVerification() {
			@Override
			public boolean verifyHost(String host, SshPublicKey pk) throws SshException {
				try {
					return SshKeyUtils.getPublicKey(node.verifiedHostKey()).equals(pk);
				} catch (IOException e) {
					log.error("Failed to load verified host key for node: " + node.uuid(), e);
					return false;
				}
			}
		});

	        SshClient client;
	        try {
	        	client = SshClientBuilder.create()
	        		.withTarget(normalizedHost, port)
	        		.withUsername(normalizedUser)
	        		.withSshContext(context)
	        		.withConnectTimeout(Duration.ofSeconds(timeout))
	        		.build();
	        } catch (IOException | SshException ex) {
	        	throw credentialsPrompt(node,
	        			"Stored credentials could not authenticate to the remote host. Provide updated password and/or key credentials.",
	        			false);
	        }
	        SshConnection con = client.getConnection();
	        con.getAuthenticatedFuture().waitFor(Duration.ofSeconds(timeout));

		if(con.getAuthenticatedFuture().isSuccess() && con.getAuthenticatedFuture().isDone()) {
			return client;
		} else {
			throw credentialsPrompt(node,
					"SSH authentication failed. Provide valid password and/or private key credentials.",
					false);
		}
	}

	private int extractPort(String normalizedHost) {
		return normalizedHost.contains(":") ? Integer.parseInt(normalizedHost.substring(normalizedHost.lastIndexOf(':') + 1)) : 22;
	}

	private SshPublicKey getHostKey(String host, int port, int timeout) throws IOException, SshException {
		return SshClientBuilder.create()
	        		.withTarget(host, port)
	        		.withUsername("guest"	)
	        		.withConnectTimeout(Duration.ofSeconds(timeout))
	        		.build().getHostKey();
	}
	private ToolSuspensionException credentialsPrompt(VorkNode node,
											 String description,
											 boolean passphraseRequired) {
		String passwordKey = secretKeyForPassword(node.uuid());
		String keyKey = secretKeyForPrivateKey(node.uuid());
		String passphraseKey = secretKeyForPassphrase(node.uuid());

		List<FormField> fields = new ArrayList<>();
		fields.add(new FormField(passwordKey, "password", "SSH Password", "Optional password", false, FieldSource.SECRET, List.of()));
		fields.add(new FormField(keyKey, "textarea", "SSH Private Key", "Optional private key PEM/OpenSSH text", false, FieldSource.SECRET, List.of()));
		fields.add(new FormField(passphraseKey, "password", "SSH Key Passphrase", "Optional key passphrase", passphraseRequired, FieldSource.SECRET, List.of()));

		InteractionFormSchema formSchema = new InteractionFormSchema(
				"AUTHORIZE_TOOL",
				"SSH Credentials Required",
				description,
				fields,
				List.of(
						new FormAction("ONCE", "Save & Continue", "primary"),
						new FormAction("DENIED", "Cancel", "danger")
				));

		return new ToolSuspensionException("executeTerminalCommand", "{}", description, formSchema);
	}

	private VorkNode resolveNode(VorkUser principal, String host, int port, String username) throws IOException, SshException {

		SshPublicKey currentHostKey = getHostKey(host, port, 5);

		List<VorkNode> nodes = listNodesForHost(principal, host);
		String resolvedUsername = resolveRequestedUsername(host, username);

		if (resolvedUsername == null || resolvedUsername.isBlank()) {
			if (nodes.size() == 1) {
				return verifyHostKey(nodes.get(0), currentHostKey);
			}
			throw usernamePrompt(host, nodes);
		}

		for (VorkNode node : nodes) {
			if (resolvedUsername.equals(node.username())) {
				return verifyHostKey(node, currentHostKey);
			}
		}

		Object approval = ToolExecutionContext.get("HOST_KEY_VERIFICATION");
	
		if ("true".equals(approval)) {
				long now = System.currentTimeMillis();
				VorkNode created = new VorkNode(
						UUID.randomUUID().toString(),
						principal.uuid(),
						host,
						resolvedUsername,
						now,
						now, 
						SshKeyUtils.getFormattedKey(currentHostKey, "Vork SSH Host Key"));
				nodeRepository.save(created);
				return created;
		}

		throw hostKeyPrompt(host, currentHostKey, null);	
	}

	private VorkNode verifyHostKey(VorkNode node, SshPublicKey currentHostKey) throws IOException, SshException {
		
		if(StringUtils.isNotBlank(node.verifiedHostKey()) 
			&&  SshKeyUtils.getPublicKey(node.verifiedHostKey()).equals(currentHostKey))	{
			return node;
		} else {
			throw hostKeyPrompt(node.host(), currentHostKey, node.verifiedHostKey());
		}
	}

	private List<VorkNode> listNodesForHost(VorkUser principal, String host) {
		try (Stream<VorkNode> stream = nodeRepository.search(
				0,
				200,
				"createdAt",
				SortOrder.ASC,
				SearchQuery.eq("ownerUserUuid", principal.uuid()),
				SearchQuery.eq("host", host))) {
			return stream.toList();
		}
	}

	private static String resolveRequestedUsername(String host, String explicitUsername) {
		if (explicitUsername != null && !explicitUsername.isBlank()) {
			return explicitUsername.trim();
		}
		String contextKey = usernameContextKeyForHost(host);
		Object contextValue = ToolExecutionContext.get(contextKey);
		if (contextValue == null) {
			return null;
		}
		String username = String.valueOf(contextValue).trim();
		return username.isBlank() ? null : username;
	}

	private static ToolSuspensionException usernamePrompt(String host, List<VorkNode> nodes) {
		String key = usernameContextKeyForHost(host);
		List<String> knownUsers = nodes.stream()
				.map(VorkNode::username)
				.distinct()
				.sorted()
				.toList();

		String description;
		String placeholder;
		if (nodes.isEmpty()) {
			description = "Provide the SSH username for host '" + host + "'. A new node entry will be created.";
			placeholder = "e.g. root, ubuntu, ec2-user";
		} else {
			description = "Multiple SSH usernames exist for host '" + host + "'. Choose one or provide a new username.";
			placeholder = "Known: " + String.join(", ", knownUsers);
		}

		InteractionFormSchema formSchema = new InteractionFormSchema(
				"AUTHORIZE_TOOL",
				"SSH Username Required",
				description,
				List.of(new FormField(
						key,
						"text",
						"SSH Username",
						placeholder,
						true,
						FieldSource.CONTEXT,
						knownUsers)),
				List.of(
						new FormAction("ONCE", "Continue", "primary"),
						new FormAction("DENIED", "Cancel", "danger")));

		return new ToolSuspensionException(
				"executeTerminalCommand",
				"",
				"An SSH username is required before credentials can be resolved.",
				formSchema);
	}

	private static ToolSuspensionException hostKeyPrompt(String host, SshPublicKey currentHostKey, String verifiedHostKey) throws IOException, SshException {

		String description;
		String placeholder;
		if (verifiedHostKey == null) {
			placeholder = "The authenticity of host '" + host + "' can't be established.";
			description = currentHostKey.getAlgorithm() + " key fingerprint is " + currentHostKey.getFingerprint();
		} else {
			SshPublicKey verifiedKey = SshKeyUtils.getPublicKey(verifiedHostKey);
			placeholder = "The identity of host '\" + host + \"' does not match the verified host key.";
			description = "Expected " + verifiedKey.getAlgorithm() + "/" + verifiedKey + " but recieved" +
							 currentHostKey.getAlgorithm() + "/" + currentHostKey.getFingerprint();
		}

		List<FormField> fields = List.of(
			new FormField(
				"HOST_KEY_VERIFICATION", 
				"CHECKBOX", 
				description, 
				"I trust this host key.", 
				true, 
				FieldSource.CONTEXT, // CRITICAL: This bypasses the LLM history!,
				Collections.emptyList()
			)
		);

		InteractionFormSchema schema = new InteractionFormSchema(
			"HOST_KEY_VERIFICATION",
			"SSH Host Key Verification",
			description,
			fields,
			List.of(new FormAction("ONCE", "Trust & Save Node", "warning"),
						new FormAction("DENIED", "Cancel", "danger"))
		);

		return new ToolSuspensionException(
				"executeTerminalCommand",
				"",
				placeholder,
				schema);
	}

	private static String secretKeyForPassword(String nodeUuid) {
		return String.format("ssh-password-%s", nodeUuid);
	}

	private static String secretKeyForPrivateKey(String nodeUuid) {
		return String.format("ssh-key-%s", nodeUuid);
	}

	private static String secretKeyForPassphrase(String nodeUuid) {
		return String.format("ssh-passphrase-%s", nodeUuid);
	}

	private static String usernameContextKeyForHost(String host) {
		return "ssh-node-username-" + host.replaceAll("[^A-Za-z0-9_-]", "_");
	}

	private static String normalizeHost(String host) {
		return host.trim().toLowerCase(Locale.ROOT);
	}

	private static VorkUser currentPrincipalUser() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
			throw new IllegalStateException("No authenticated principal available for credential lookup");
		}
		return new VorkUser(auth.getName(), "", "USER", 0L, 0L);
	}
}
