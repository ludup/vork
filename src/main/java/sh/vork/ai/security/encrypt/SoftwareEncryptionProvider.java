package sh.vork.ai.security.encrypt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import sh.vork.ai.config.ApplicationProperties;
import com.jadaptive.hsm.encrypt.SoftwareEncryptionProvider.Builder;

@Component
public class SoftwareEncryptionProvider implements EncryptionProvider {

	private static final String ENCRYPTION_TAG = "!!SFT!!";

	private com.jadaptive.hsm.encrypt.SoftwareEncryptionProvider delegate;

	@Autowired
	private ApplicationProperties properties;

	@Override
	public int priority() {
		return 100;
	}

	@Override
	public void init() throws Exception {
		Builder builder = com.jadaptive.hsm.encrypt.SoftwareEncryptionProvider.builder()
				.setEnabled(properties.getValue("software.enabled", true))
				.setKeystoreDir(properties.getValue("software.keystore.dir", "conf.d"))
				.setKeystoreSubdir(properties.getValue("software.keystore.conf", "private"))
				.setKeystoreFilename(properties.getValue("software.keystore.filename", "software-encryption.p12"))
				.setKeystoreAlias(properties.getValue("software.keystore.alias", "software-encryption"))
				.setKeystorePassword(properties.getValue("software.keystore.password", "changeit"))
				.setKeySize(properties.getValue("software.keysize", 2048));

		delegate = builder.build();
		delegate.init();
	}

	@Override
	public String encrypt(String string) throws Exception {
		return delegate.encrypt(string);
	}

	@Override
	public String decrypt(String substring) throws Exception {
		return delegate.decrypt(substring);
	}

	@Override
	public String getTag() {
		return ENCRYPTION_TAG;
	}
}
