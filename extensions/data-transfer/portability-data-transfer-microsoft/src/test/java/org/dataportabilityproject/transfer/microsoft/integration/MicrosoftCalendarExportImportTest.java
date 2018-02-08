package org.dataportabilityproject.transfer.microsoft.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import okhttp3.OkHttpClient;
import org.dataportabilityproject.spi.transfer.provider.ExportResult;
import org.dataportabilityproject.transfer.microsoft.calendar.MicrosoftCalendarExporter;
import org.dataportabilityproject.transfer.microsoft.transformer.TransformerServiceImpl;
import org.dataportabilityproject.types.transfer.auth.TokenAuthData;
import org.dataportabilityproject.types.transfer.models.calendar.CalendarContainerResource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies Calendar export/import using mock HTTP endpoints that replay responses from the Microsoft Graph API.
 */
public class MicrosoftCalendarExportImportTest {
    private MockWebServer server;
    private OkHttpClient client;
    private ObjectMapper mapper;
    private TransformerServiceImpl transformerService;
    private TokenAuthData token;

    @Test
    public void testExportImport() throws Exception {
        server.enqueue(new MockResponse().setBody(CALENDARS_RESPONSE));
        server.enqueue(new MockResponse().setBody(CALENDAR1_EVENTS_RESPONSE));
        server.enqueue(new MockResponse().setBody(CALENDAR2_EVENTS_RESPONSE));

        server.start();

        HttpUrl baseUrl = server.url("");
        MicrosoftCalendarExporter exporter = new MicrosoftCalendarExporter(baseUrl.toString(), client, mapper, transformerService);

        ExportResult<CalendarContainerResource> resource = exporter.export(token);

        CalendarContainerResource calendarResource = resource.getExportedData();

        Assert.assertEquals(2, calendarResource.getCalendars().size());
        Assert.assertFalse(calendarResource.getCalendars().stream().anyMatch(c -> "Calendar1".equals(c.getId()) && "Calendar2".equals(c.getId())));

        Assert.assertEquals(2, calendarResource.getEvents().size());
        Assert.assertFalse(calendarResource.getEvents().stream().anyMatch(e -> "Test Appointment 1".equals(e.getTitle()) && "Test Appointment 2".equals(e.getTitle())));
    }

    @Before
    public void setUp() throws Exception {
        client = new OkHttpClient.Builder().build();
        mapper = new ObjectMapper();
        transformerService = new TransformerServiceImpl();
        token = new TokenAuthData("token123");
        server = new MockWebServer();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private static final String CALENDARS_RESPONSE = "{" +
            "    \"@odata.context\": \"https://graph.microsoft.com/v1.0/$metadata#users('foo%40bar.com')/calendars\"," +
            "    \"value\": [" +
            "        {" +
            "            \"id\": \"Calendar1\"," +
            "            \"name\": \"Calendar\"," +
            "            \"color\": \"auto\"," +
            "            \"changeKey\": \"1==\"," +
            "            \"canShare\": true," +
            "            \"canViewPrivateItems\": true," +
            "            \"canEdit\": true," +
            "            \"owner\": {" +
            "                \"name\": \"Foo Bar\"," +
            "                \"address\": \"foo@outlook.com\"" +
            "            }" +
            "        }," +
            "        {" +
            "            \"id\": \"Calendar2\"," +
            "            \"name\": \"Test\"," +
            "            \"color\": \"auto\"," +
            "            \"changeKey\": \"2==\"," +
            "            \"canShare\": true," +
            "            \"canViewPrivateItems\": true," +
            "            \"canEdit\": true," +
            "            \"owner\": {" +
            "                \"name\": \"Baz Qux\"," +
            "                \"address\": \"baz@outlook.com\"" +
            "            }" +
            "        }" +
            "    ]" +
            "}";

    private static final String CALENDAR1_EVENTS_RESPONSE = "{" +
            "    \"@odata.context\": \"https://graph.microsoft.com/v1.0/$metadata#users(‘foo%40bar.com')/calendars(‘Calendar123’)/events\"," +
            "    \"value\": [" +
            "        {" +
            "            \"@odata.etag\": \"W/\\\"J9QFpZ0REkSkaxRkN3kHHwAB54nFhA==\\\"\"," +
            "            \"id\": \"Event1\"," +
            "            \"createdDateTime\": \"2018-02-07T09:11:50.9516391Z\"," +
            "            \"lastModifiedDateTime\": \"2018-02-07T14:30:46.2546211Z\"," +
            "            \"changeKey\": \"J9QFpZ0REkSkaxRkN3kHHwAB54nFhA==\"," +
            "            \"categories\": []," +
            "            \"originalStartTimeZone\": \"Pacific Standard Time\"," +
            "            \"originalEndTimeZone\": \"Pacific Standard Time\"," +
            "            \"iCalUId\": \"1\"," +
            "            \"reminderMinutesBeforeStart\": 15," +
            "            \"isReminderOn\": true," +
            "            \"hasAttachments\": false," +
            "            \"subject\": \"Test Appointment 1\"," +
            "            \"bodyPreview\": \"\"," +
            "            \"importance\": \"normal\"," +
            "            \"sensitivity\": \"normal\"," +
            "            \"isAllDay\": false," +
            "            \"isCancelled\": false," +
            "            \"isOrganizer\": true," +
            "            \"responseRequested\": true," +
            "            \"seriesMasterId\": null," +
            "            \"showAs\": \"busy\"," +
            "            \"type\": \"singleInstance\"," +
            "            \"webLink\": \"\"," +
            "            \"onlineMeetingUrl\": null," +
            "            \"responseStatus\": {" +
            "                \"response\": \"organizer\"," +
            "                \"time\": \"0001-01-01T00:00:00Z\"" +
            "            }," +
            "            \"body\": {" +
            "                \"contentType\": \"html\"," +
            "                \"content\": \"<html>\\r\\n<head>\\r\\n<meta http-equiv=\\\"Content-Type\\\" content=\\\"text/html; charset=utf-8\\\">\\r\\n<meta content=\\\"text/html; charset=us-ascii\\\">\\r\\n<style type=\\\"text/css\\\" style=\\\"display:none\\\">\\r\\n<!--\\r\\np\\r\\n\\t{margin-top:0;\\r\\n\\tmargin-bottom:0}\\r\\n-->\\r\\n</style>\\r\\n</head>\\r\\n<body dir=\\\"ltr\\\">\\r\\n<div id=\\\"divtagdefaultwrapper\\\" dir=\\\"ltr\\\" style=\\\"font-size:12pt; color:#000000; font-family:Calibri,Helvetica,sans-serif\\\">\\r\\n<p style=\\\"margin-top:0; margin-bottom:0\\\"><br>\\r\\n</p>\\r\\n</div>\\r\\n</body>\\r\\n</html>\\r\\n\"" +
            "            }," +
            "            \"start\": {" +
            "                \"dateTime\": \"2018-02-14T18:00:00.0000000\"," +
            "                \"timeZone\": \"UTC\"" +
            "            }," +
            "            \"end\": {" +
            "                \"dateTime\": \"2018-02-14T18:30:00.0000000\"," +
            "                \"timeZone\": \"UTC\"" +
            "            }," +
            "            \"location\": {" +
            "                \"displayName\": \"\"," +
            "                \"address\": {}" +
            "            }," +
            "            \"recurrence\": null," +
            "            \"attendees\": [" +
            "                {" +
            "                    \"type\": \"required\"," +
            "                    \"status\": {" +
            "                        \"response\": \"none\"," +
            "                        \"time\": \"0001-01-01T00:00:00Z\"" +
            "                    }," +
            "                    \"emailAddress\": {" +
            "                        \"name\": \"Test Test1\"," +
            "                        \"address\": \"foo@foo.com\"" +
            "                    }" +
            "                }" +
            "            ]," +
            "            \"organizer\": {" +
            "                \"emailAddress\": {" +
            "                    \"name\": \"Foo Bar\"," +
            "                    \"address\": \"foo@outlook.com\"" +
            "                }" +
            "            }" +
            "        }" +
            "    ]" +
            "}";

    private static final String CALENDAR2_EVENTS_RESPONSE = "{" +
            "    \"@odata.context\": \"https://graph.microsoft.com/v1.0/$metadata#users(‘foo%40bar.com')/calendars(‘Calendar123’)/events\"," +
            "    \"value\": [" +
            "        {" +
            "            \"@odata.etag\": \"W/\\\"J9QFpZ0REkSkaxRkN3kHHwAB54nFhA==\\\"\"," +
            "            \"id\": \"Event1\"," +
            "            \"createdDateTime\": \"2018-02-07T09:11:50.9516391Z\"," +
            "            \"lastModifiedDateTime\": \"2018-02-07T14:30:46.2546211Z\"," +
            "            \"changeKey\": \"J9QFpZ0REkSkaxRkN3kHHwAB54nFhA==\"," +
            "            \"categories\": []," +
            "            \"originalStartTimeZone\": \"Pacific Standard Time\"," +
            "            \"originalEndTimeZone\": \"Pacific Standard Time\"," +
            "            \"iCalUId\": \"1\"," +
            "            \"reminderMinutesBeforeStart\": 15," +
            "            \"isReminderOn\": true," +
            "            \"hasAttachments\": false," +
            "            \"subject\": \"Test Appointment 2\"," +
            "            \"bodyPreview\": \"\"," +
            "            \"importance\": \"normal\"," +
            "            \"sensitivity\": \"normal\"," +
            "            \"isAllDay\": false," +
            "            \"isCancelled\": false," +
            "            \"isOrganizer\": true," +
            "            \"responseRequested\": true," +
            "            \"seriesMasterId\": null," +
            "            \"showAs\": \"busy\"," +
            "            \"type\": \"singleInstance\"," +
            "            \"webLink\": \"\"," +
            "            \"onlineMeetingUrl\": null," +
            "            \"responseStatus\": {" +
            "                \"response\": \"organizer\"," +
            "                \"time\": \"0001-01-01T00:00:00Z\"" +
            "            }," +
            "            \"body\": {" +
            "                \"contentType\": \"html\"," +
            "                \"content\": \"<html>\\r\\n<head>\\r\\n<meta http-equiv=\\\"Content-Type\\\" content=\\\"text/html; charset=utf-8\\\">\\r\\n<meta content=\\\"text/html; charset=us-ascii\\\">\\r\\n<style type=\\\"text/css\\\" style=\\\"display:none\\\">\\r\\n<!--\\r\\np\\r\\n\\t{margin-top:0;\\r\\n\\tmargin-bottom:0}\\r\\n-->\\r\\n</style>\\r\\n</head>\\r\\n<body dir=\\\"ltr\\\">\\r\\n<div id=\\\"divtagdefaultwrapper\\\" dir=\\\"ltr\\\" style=\\\"font-size:12pt; color:#000000; font-family:Calibri,Helvetica,sans-serif\\\">\\r\\n<p style=\\\"margin-top:0; margin-bottom:0\\\"><br>\\r\\n</p>\\r\\n</div>\\r\\n</body>\\r\\n</html>\\r\\n\"" +
            "            }," +
            "            \"start\": {" +
            "                \"dateTime\": \"2018-02-14T19:00:00.0000000\"," +
            "                \"timeZone\": \"UTC\"" +
            "            }," +
            "            \"end\": {" +
            "                \"dateTime\": \"2018-02-14T19:30:00.0000000\"," +
            "                \"timeZone\": \"UTC\"" +
            "            }," +
            "            \"location\": {" +
            "                \"displayName\": \"\"," +
            "                \"address\": {}" +
            "            }," +
            "            \"recurrence\": null," +
            "            \"attendees\": [" +
            "                {" +
            "                    \"type\": \"required\"," +
            "                    \"status\": {" +
            "                        \"response\": \"none\"," +
            "                        \"time\": \"0001-01-01T00:00:00Z\"" +
            "                    }," +
            "                    \"emailAddress\": {" +
            "                        \"name\": \"Test Test2\"," +
            "                        \"address\": \"foo@foo.com\"" +
            "                    }" +
            "                }" +
            "            ]," +
            "            \"organizer\": {" +
            "                \"emailAddress\": {" +
            "                    \"name\": \"Foo Bar\"," +
            "                    \"address\": \"foo@outlook.com\"" +
            "                }" +
            "            }" +
            "        }" +
            "    ]" +
            "}";
}
