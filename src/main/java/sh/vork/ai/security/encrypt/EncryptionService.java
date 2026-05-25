package sh.vork.ai.security.encrypt;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class EncryptionService {

	private static Logger log = LoggerFactory.getLogger(EncryptionService.class);
	
	private final Map<String,EncryptionProvider> encryptionProviders = new HashMap<>();
	
	@Autowired
	private ApplicationContext context;

	private EncryptionProvider defaultProvider;
	
	@PostConstruct
	private void postConstruct() {
		log.info("Looking for encryption providers ..");
		
		for(var provider : context.getBeansOfType(EncryptionProvider.class).values()) {
			try {
				provider.init();
				if(provider.getTag() == null || provider.getTag().length() != 7) {
					log.warn("  {} - Ignoring. Tag {} as it does not confirm to strict requirements.", provider.getClass().getName(), provider.getTag());
					continue;
				}
				encryptionProviders.put(provider.getTag(), provider);
				log.info("  {} - Activated.", provider.getClass().getName());
				continue;
			}
			catch(Exception e) {
				log.error("  {} - Ignoring. {}", provider.getClass().getName(), e.getMessage());
				if(log.isDebugEnabled()) {
					log.error("Failed to init provider.", e);
				}
			}
		}
		
		if(encryptionProviders.isEmpty()) {
			throw new IllegalStateException("No encryption provider. Cannot continue.");
		}
		
		List<EncryptionProvider> providers = encryptionProviders.values()
				.stream()
				.sorted((a, b) -> Integer.compare(a.priority(), b.priority())).toList();
		
		defaultProvider = providers.get(0);
	}
	
	public boolean isEncrypted(String value) {
		return encryptionProviders.containsKey(StringUtils.substring(value, 0, 7));
	}

	public String encrypt(String value) {

		if(isEncrypted(value)) {
			return value;
		}
		
		return encryptAndTag(value, defaultProvider);
	}	
	private String encryptAndTag(String value, EncryptionProvider provider) {
    try {
        int keyLength = Math.min(Cipher.getMaxAllowedKeyLength("AES"), 256) / 8;
        
        SecureRandom rnd = new SecureRandom();
        byte[] rawkey = new byte[keyLength];
        rnd.nextBytes(rawkey);

        // GCM optimizes around a 12-byte (96-bit) IV
        byte[] iv = new byte[12]; 
        rnd.nextBytes(iv);

        StringBuilder buffer = new StringBuilder();
        buffer.append(Base64.getEncoder().encodeToString(rawkey));
        buffer.append("|");
        buffer.append(Base64.getEncoder().encodeToString(iv));
        buffer.append("|");
        
        // Pass to the authenticated GCM runner
        byte[] ciphertext = encryptAES(value, rawkey, iv);
        buffer.append(Base64.getEncoder().encodeToString(ciphertext));
        
        return provider.getTag().concat(provider.encrypt(buffer.toString()));
    } catch (Exception e) {
        throw new IllegalStateException("GCM Envelope build failed", e);
    }
}

private byte[] encryptAES(String value, byte[] key, byte[] iv) throws Exception {
    // 1. Authenticated Counter Mode with NO Padding
    Cipher aesCipherForEncryption = Cipher.getInstance("AES/GCM/NoPadding", "BC");

    SecretKey secretKeySpec = new SecretKeySpec(key, "AES");
    
    // 2. Specify a 128-bit Authentication Tag length alongside the 12-byte IV
    GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
    
    aesCipherForEncryption.init(Cipher.ENCRYPT_MODE, secretKeySpec, parameterSpec);

    byte[] byteDataToEncrypt = value.getBytes(StandardCharsets.UTF_8);
    return aesCipherForEncryption.doFinal(byteDataToEncrypt);
}

	public String decrypt(String value) {
		
		if(!isEncrypted(value)) {
			return value;
		}
		
		String tag = StringUtils.substring(value, 0, 7);
		EncryptionProvider encryptionProvider = encryptionProviders.get(tag);

		try {
			String data = encryptionProvider.decrypt(value.substring(7));
			String[] elements = data.split("\\|");
			byte[] key = Base64.getDecoder().decode(elements[0]);
			byte[] iv = Base64.getDecoder().decode(elements[1]);
			byte[] encrypted = Base64.getDecoder().decode(elements[2]);
			
			String tmp = new String(decryptAES(encrypted, key, iv), "UTF-8");
			return tmp;
		} catch(IllegalArgumentException  iae) {
			throw new IllegalStateException(
					"Detected likely key change. An attempt has been made to decrypt a piece of information that was encrypted "
					+ "with a different key. This may be due to the current encrypt service configuration being changed, or  "
					+ "the private key has been corrupted. It may also occur if this nodes Mongo database is shared with other "
					+ "nodes in a cluster, but the encryption service configuration on each node does not match. There is no "
					+ "recovery from this error other than correcting the encryption configuration or recovering the encryption "
					+ "key. ", iae);
		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}	
	}
	
	private byte[] decryptAES(byte[] value, byte[] key, byte[] iv) throws Exception {
	    // 1. Authenticated Counter Mode with NO Padding
	    Cipher aesCipherForDecryption = Cipher.getInstance("AES/GCM/NoPadding", "BC");
	
	    SecretKey secretKeySpec = new SecretKeySpec(key, "AES");
	    
	    // 2. Specify a 128-bit Authentication Tag length alongside the 12-byte IV
	    GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
	    
	    aesCipherForDecryption.init(Cipher.DECRYPT_MODE, secretKeySpec, parameterSpec);
	
	    return aesCipherForDecryption.doFinal(value);
	}
}
