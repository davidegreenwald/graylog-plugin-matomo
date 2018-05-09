package uk.co.znik.graylog.plugins.matomo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.apache.commons.codec.digest.DigestUtils;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.BooleanField;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.outputs.MessageOutput;
import org.graylog2.plugin.outputs.MessageOutputConfigurationException;
import org.graylog2.plugin.streams.Stream;
import org.piwik.java.tracking.PiwikRequest;
import org.piwik.java.tracking.PiwikTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.List;


public class MatomoOutput implements MessageOutput {

    private static final Logger LOG = LoggerFactory.getLogger(MatomoOutput.class);

    private static final String MATOMO_URL = "matomo_url";
    private static final String MATOMO_TOKEN = "matomo_token";
    private static final String MATOMO_SITE_CREATE = "matomo_site_create";

    private MatomoInstance matomoInstance;
    private PiwikTracker matomoTracker;
    private boolean running;
    private final Configuration configuration;

    @Inject
    public MatomoOutput(@Assisted Configuration c) throws MessageOutputConfigurationException {
        if (!checkConfiguration(c)) {
            throw new MessageOutputConfigurationException("Missing or incorrect configuration.");
        }
        configuration = c;
        matomoInstance = new MatomoInstance(c.getString(MATOMO_URL), c.getString(MATOMO_TOKEN));
        matomoTracker = new PiwikTracker(c.getString(MATOMO_URL)+"/piwik.php");
        running = true;
    }

    private boolean checkConfiguration(Configuration c) {
        return c.stringIsSet(MATOMO_URL)
                && c.stringIsSet(MATOMO_TOKEN);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void write(Message message) throws Exception {
        //LOG.warn("DEBUG: message is: " + message);
        String request_scheme = (String) message.getField("request_scheme")+"://";
        String host = (String) message.getField("host");
        String request_uri = (String) message.getField("request_uri");
        String remote_addr = (String) message.getField("remote_addr");
        String http_user_agent = (String) message.getField("http_user_agent");

        String visitorId = DigestUtils.sha1Hex(remote_addr+http_user_agent).substring(0,16);

        // we don't have host/site or request_uri which is required, skip this message
        if ((host == null) || (request_uri == null) || (request_scheme == null))
            return;
        // http or https?
        MatomoSite matomoSite = matomoInstance.getSite(host);
        if (matomoSite == null && configuration.getBoolean(MATOMO_SITE_CREATE)) {
            matomoSite = matomoInstance.addNewSite(host, request_scheme+host);
        }

        URL actionUrl = new URL(request_scheme+host+request_uri);

        PiwikRequest piwikRequest = new PiwikRequest(matomoSite.getIdsite(), actionUrl);
        piwikRequest.setAuthToken(configuration.getString(MATOMO_TOKEN)); // must be first

        piwikRequest.setVisitorId(visitorId);
        piwikRequest.setVisitorIp(remote_addr);
        piwikRequest.setHeaderUserAgent(http_user_agent);

        matomoTracker.sendRequest(piwikRequest);

        //sender.sendMessage(message);
    }

    @Override
    public void write(List<Message> messages) throws Exception {


    }

    @Override
    public void stop() {
        //sender.stop()
        running = false;
    }

    @FactoryClass
    public interface Factory extends MessageOutput.Factory<MatomoOutput> {

        @Override
        MatomoOutput create(Stream stream, Configuration configuration);

        @Override
        Config getConfig();

        @Override
        Descriptor getDescriptor();
    }

    @ConfigClass
    public static class Config extends MessageOutput.Config {
        @Override
        public ConfigurationRequest getRequestedConfiguration() {
            final ConfigurationRequest configurationRequest = new ConfigurationRequest();

            configurationRequest.addField(new TextField(
                    MATOMO_URL, "Matomo URI", "",
                    "HTTP address of matomo installation",
                    ConfigurationField.Optional.NOT_OPTIONAL)
            );
            configurationRequest.addField(new TextField(
                    MATOMO_TOKEN, "Matomo Token", "",
                    "Matomo user token to access API",
                    ConfigurationField.Optional.NOT_OPTIONAL)
            );
            configurationRequest.addField(new BooleanField(
                    MATOMO_SITE_CREATE, "Create sites", false,
                    "Create sites in matomo installation if not exist from $HTTP_HOST.")
            );
            return configurationRequest;
        }
    }

    public static class Descriptor extends MessageOutput.Descriptor {
        public Descriptor() {
            super("Matomo Output", false, "",
                    "Writes messages to your Matomo installation via it's API.");
        }
    }

}
