package org.apache.poi.benchmark.email;

import org.apache.commons.mail.EmailAttachment;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.ImageHtmlEmail;
import org.apache.commons.mail.MultiPartEmail;
import org.apache.commons.mail.resolver.DataSourceUrlResolver;
import org.dstadler.commons.email.EmailConfig;
import org.dstadler.commons.email.MailserverConfig;

import javax.mail.AuthenticationFailedException;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;


/**
 *
 * @author dominik.stadler
 */
public class EmailSender {
    //private static final Log logger = LogFactory.getLog(EmailSender.class);

	// error messages
	//private static final String CANNOT_SEND_EMAIL_WITHOUT_SMTP_SERVER = "Cannot send email without SMTP server set in the configuration.";
	private static final String CANNOT_SEND_EMAIL_NO_EMAIL_DATA = "Cannot send email, no email data provided.";
	private static final String CANNOT_SEND_EMAIL_NO_MAIL_SERVER_CONFIGURATION = "Cannot send email, no mail server configuration available";
	//private static final String CANNOT_SEND_EMAIL_NO_USER_DATA = "Cannot send email, no user data provided.";

	private static final char SCOLON = ';';
	private static final char COMMA = ':';

	/**
	 * Send an email with the specified files as attachments.
	 *
	 *	Note: This method does not resolve the email addresses in EmailConfig from users/groups, use the
	 *		other provided methods in this class to do this.
	 *
	 * Note: This does not perform HTML image replacement!
	 *
	 * @throws IOException
	 */
	public void sendAttachmentEmail(List<File> attachments, MailserverConfig mailserverConfig,
									EmailConfig emailConfig, String html) throws IOException {
		if(mailserverConfig == null) {
			throw new IOException(CANNOT_SEND_EMAIL_NO_MAIL_SERVER_CONFIGURATION);
		}

		if(emailConfig == null) {
			throw new IOException(CANNOT_SEND_EMAIL_NO_EMAIL_DATA);
		}

		if(attachments== null || attachments.size() == 0) {
			throw new IOException("Cannot send email, no attachments specified.");
		}

		try {
			final ImageHtmlEmail email = new ImageHtmlEmail();
	        email.setDataSourceResolver(new DataSourceUrlResolver(new File(".").toURI().toURL(), true));

			for(File report : attachments) {
				// Create the attachment
				EmailAttachment attachment = new EmailAttachment();
				attachment.setPath(report.getAbsolutePath());
				attachment.setDisposition(EmailAttachment.ATTACHMENT);
				attachment.setDescription("The generated report");
				attachment.setName(report.getName());

				// add the attachment
				email.attach(attachment);
			}

			setSMTPConfig(email, mailserverConfig);

			try {
				setEmailConfig(email, emailConfig, mailserverConfig.getSubjectPrefix());
			} catch (AddressException e) {
				throw new IOException("AddressException: " + getExceptionText(e), e);
			}

			email.setMsg("Your email client does not support HTML messages");

			if(html != null && html.length() > 0) {
				email.setHtmlMsg(html);
			}

			email.send();
		} catch (EmailException e) {
			throw new IOException("Sending the email caused an exception: " + getExceptionText(e));	// NOPMD - I do not want to pass EmailException to the outside
		}
	}

	/**
	 * Helper method to populate the javax-email components with the Mailserver configuration
	 */
	private void setSMTPConfig(MultiPartEmail email, MailserverConfig config) {
		// set properties on the Email object
		int port = config.getServerPort();
		email.setHostName(config.getServerAddress());
		if (port != -1) {
			email.setSmtpPort(port);
		}

		// set optional SMTP username and password
		String smtpUser = config.getUserId();
		String smtpPassword = config.getPassword();
		if (smtpUser != null && !smtpUser.isEmpty() && smtpPassword != null && !smtpPassword.isEmpty()) {
			email.setAuthentication(smtpUser, smtpPassword);
		}

		// set optional bounce address
		String emailBounce = config.getBounce();
		if (emailBounce != null && !emailBounce.isEmpty()) {
			email.setBounceAddress(emailBounce);
		}

		// set debug if specified to get more output in case it does not work
		// for some technical reason
		email.setDebug(config.isDebug());

		// some SMTP hosts require SSL, e.g. Google Mail seems to in some cases...
		email.setSSLOnConnect(config.isSSLEnabled());
	}

	/**
	 * Helper method to populate the javax-email components with the Email configuration
	 * @throws AddressException
	 * @throws EmailException
	 */
	private void setEmailConfig(MultiPartEmail email, EmailConfig emailConfig, String subjectPrefix) throws AddressException, EmailException, IOException {
		boolean hadAddress = false;

		// JLT-17850: semicolons are replaced with commas to preemt parsing
		// errors.
		// note that a semicolon is not a RFC822 compatible recipient seperator
		// but it seems users are very used to use it as such.
		if (emailConfig.getTo() != null && !emailConfig.getTo().isEmpty()) {
			String toWithoutScolons = emailConfig.getToAsEmail().replace(SCOLON, COMMA);
			// something might go wrong in conversion from Resolved Email Address to string
			if(toWithoutScolons.length() > 0) {
				email.setTo(Arrays.asList(InternetAddress.parse(toWithoutScolons)));
				hadAddress = true;
			}
		}
		//email.setTo(Arrays.asList(new String[] { emailAddress }));

		// set optional "cc" addresses
		if (emailConfig.getCc() != null && !emailConfig.getCc().isEmpty()) {
			String ccWithoutScolons = emailConfig.getCcAsEmail().replace(SCOLON, COMMA);
			// something might go wrong in conversion from Resolved Email Address to string
			if(ccWithoutScolons.length() > 0) {
				email.setCc(Arrays.asList(InternetAddress.parse(ccWithoutScolons)));
				hadAddress = true;
			}
		}

		// set optional "bcc" addresses
		if (emailConfig.getBcc() != null && !emailConfig.getBcc().isEmpty()) {
			String bccWithoutScolons = emailConfig.getBccAsEmail().replace(SCOLON, COMMA);
			// something might go wrong in conversion from Resolved Email Address to string
			if(bccWithoutScolons.length() > 0) {
				email.setBcc(Arrays.asList(InternetAddress.parse(bccWithoutScolons)));
				hadAddress = true;
			}
		}

		// throw an error without recipient
		if(!hadAddress) {
			throw new IOException("At least one receiver address required, could not send email: '" + emailConfig.getSubject() + "' from '" + emailConfig.getFrom() + "'");
		}

		if(emailConfig.getFrom() != null) {
			email.setFrom(emailConfig.getFrom());
		}

		email.setSubject((subjectPrefix != null ? subjectPrefix : "" ) + emailConfig.getSubject());
	}

	private String getExceptionText(Throwable e) {
		final StringBuilder msg;

		// handle some exception types specially to provide more user-friendly error messages
		if(e instanceof AuthenticationFailedException) {
			msg = new StringBuilder("Authentication with the provided SMTP username and password failed");
		} else if(e.getMessage() != null) {
			msg = new StringBuilder(e.toString());
		} else {
			msg = new StringBuilder(e.getClass().getSimpleName());
		}

		// recursively add all nested exceptions to provide full error information
		if(e.getCause() != null) {
			msg.append("\n").append(getExceptionText(e.getCause()));
		}

		return msg.toString();
	}
}
