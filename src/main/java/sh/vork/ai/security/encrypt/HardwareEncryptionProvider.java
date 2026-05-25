package sh.vork.ai.security.encrypt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import sh.vork.ai.config.ApplicationProperties;
import com.jadaptive.hsm.encrypt.HardwareEncryptionProvider.Builder;

@Component
public class HardwareEncryptionProvider implements EncryptionProvider {

	private static final String ENCRYPTION_TAG = "!!HSM!!";

	private com.jadaptive.hsm.encrypt.HardwareEncryptionProvider delegate;

	@Autowired
	private ApplicationProperties properties;

	@Override
	public int priority() {
		return 10;
	}

	@Override
	public void init() throws Exception {
		Builder builder = com.jadaptive.hsm.encrypt.HardwareEncryptionProvider.builder()
				.setEnabled(properties.getValue("hardware.enabled", true))
				.setKeySize(properties.getValue("hardware.keysize", 2048))
				.setKeyLabel(properties.getValue("hardware.key.label", "jadaptive-hsm-key"))
				.setPkcs11Config(properties.getValue("hardware.pkcs11.config", ""))
				.setJcaProvider(properties.getValue("hardware.jca.provider", ""))
				.setKeystoreType(properties.getValue("hardware.keystore.type", ""))
				.setKeystorePath(properties.getValue("hardware.keystore.path", ""))
				.setKeystorePassword(properties.getValue("hardware.keystore.password", ""))
				.setCreateIfMissing(properties.getValue("hardware.jca.createIfMissing", true))
				.setNativeBackend(properties.getValue("hardware.native.backend", ""));

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
