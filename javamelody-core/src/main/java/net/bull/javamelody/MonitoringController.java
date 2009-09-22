/*
 * Copyright 2008-2009 by Emeric Vernat, Bull
 *
 *     This file is part of Java Melody.
 *
 * Java Melody is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Java Melody is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Java Melody.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.bull.javamelody;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Contrôleur au sens MVC de l'ihm de monitoring.
 * @author Emeric Vernat
 */
class MonitoringController {
	static final String HTML_CHARSET = "ISO-8859-1";
	static final String HTML_CONTENT_TYPE = "text/html; charset=ISO-8859-1";
	static final String ACTION_PARAMETER = "action";
	static final String PART_PARAMETER = "part";
	static final String PERIOD_PARAMETER = "period";
	static final String SESSION_ID_PARAMETER = "sessionId";
	static final String COLLECTOR_PARAMETER = "collector";
	static final String HEAP_HISTO_PART = "heaphisto";
	static final String PROCESSES_PART = "processes";
	static final String CURRENT_REQUESTS_PART = "currentRequests";
	static final String WEB_XML_PART = "web.xml";
	static final String POM_XML_PART = "pom.xml";
	static final String SESSIONS_PART = "sessions";
	private static final String COUNTER_PARAMETER = "counter";
	private static final String GRAPH_PARAMETER = "graph";
	private static final String RESOURCE_PARAMETER = "resource";
	private static final String FORMAT_PARAMETER = "format";
	private static final String WIDTH_PARAMETER = "width";
	private static final String HEIGHT_PARAMETER = "height";

	static {
		boolean webXmlExists = false;
		boolean pomXmlExists = false;
		try {
			final InputStream webXmlAsStream = getWebXmlAsStream();
			if (webXmlAsStream != null) {
				webXmlAsStream.close();
				webXmlExists = true;
			}
			final InputStream pomXmlAsStream = getPomXmlAsStream();
			if (pomXmlAsStream != null) {
				pomXmlAsStream.close();
				pomXmlExists = true;
			}
		} catch (final IOException e) {
			Collector.printStackTrace(e);
		}
		JavaInformations.setWebXmlExistsAndPomXmlExists(webXmlExists, pomXmlExists);
	}
	private final Collector collector;
	private final boolean collectorServer;
	private HeapHistogram heapHistogramIfCollectServer;
	private List<SessionInformations> sessionsInformationsIfCollectServer;
	private String messageForReport;

	MonitoringController(Collector collector, boolean collectorServer) {
		super();
		assert collector != null;
		this.collector = collector;
		this.collectorServer = collectorServer;
	}

	boolean isJavaInformationsNeeded(HttpServletRequest httpRequest) {
		return httpRequest.getParameter(RESOURCE_PARAMETER) == null
				&& httpRequest.getParameter(GRAPH_PARAMETER) == null
				&& httpRequest.getParameter(PART_PARAMETER) == null;
	}

	void executeActionIfNeeded(HttpServletRequest httpRequest) throws IOException {
		assert httpRequest != null;
		final String actionParameter = httpRequest.getParameter(ACTION_PARAMETER);
		if (actionParameter != null) {
			try {
				// langue préférée du navigateur, getLocale ne peut être null
				I18N.bindLocale(httpRequest.getLocale());
				// par sécurité
				Action.checkSystemActionsEnabled();
				final Action action = Action.valueOfIgnoreCase(actionParameter);
				final String counterName = httpRequest.getParameter(COUNTER_PARAMETER);
				final String sessionId = httpRequest.getParameter(SESSION_ID_PARAMETER);
				messageForReport = action.execute(collector, counterName, sessionId);
			} finally {
				I18N.unbindLocale();
			}
		}
	}

	void doReport(HttpServletRequest httpRequest, HttpServletResponse httpResponse,
			List<JavaInformations> javaInformationsList) throws IOException {
		assert httpRequest != null;
		assert httpResponse != null;
		assert javaInformationsList != null;

		final String resource = httpRequest.getParameter(RESOURCE_PARAMETER);
		if (resource != null) {
			doResource(httpResponse, resource);
			return;
		}

		// dans tous les cas sauf resource,
		// il n'y a pas de cache navigateur (sur la page html, les courbes ou le flux sérialisé)
		noCache(httpResponse);

		try {
			// langue préférée du navigateur, getLocale ne peut être null
			I18N.bindLocale(httpRequest.getLocale());

			final Period period = getPeriod(httpRequest);
			final String part = httpRequest.getParameter(PART_PARAMETER);
			final String graph = httpRequest.getParameter(GRAPH_PARAMETER);
			if (part == null && graph != null) {
				doGraph(httpRequest, httpResponse, period, graph);
				return;
			} else if (WEB_XML_PART.equalsIgnoreCase(part)) {
				doWebXml(httpResponse);
				return;
			} else if (POM_XML_PART.equalsIgnoreCase(part)) {
				doPomXml(httpResponse);
				return;
			}
			final String format = httpRequest.getParameter(FORMAT_PARAMETER);
			if (format == null || "html".equalsIgnoreCase(format)) {
				doCompressedHtml(httpRequest, httpResponse, javaInformationsList, period, part);
			} else if ("pdf".equalsIgnoreCase(format)) {
				doPdf(httpRequest, httpResponse, period, javaInformationsList);
			} else {
				// l'appelant (un serveur d'agrégation par exemple) peut appeler
				// la page monitoring avec un format "serialized" ou "xml" en paramètre
				// pour avoir les données au format sérialisé java ou xml
				final TransportFormat transportFormat = TransportFormat.valueOfIgnoreCase(format);
				final Serializable serializable = createSerializable(httpRequest,
						javaInformationsList);
				httpResponse.setContentType(transportFormat.getMimeType());
				transportFormat.writeSerializableTo(serializable, httpResponse.getOutputStream());

				if ("stop".equalsIgnoreCase(httpRequest.getParameter(COLLECTOR_PARAMETER))) {
					// on a été appelé par un serveur de collecte qui fera l'aggrégation dans le temps,
					// le stockage et les courbes, donc on arrête le timer s'il est démarré
					// et on vide les stats pour que le serveur de collecte ne récupère que les deltas
					collector.stop();
				}
			}
		} finally {
			I18N.unbindLocale();
		}
	}

	static void noCache(HttpServletResponse httpResponse) {
		httpResponse.addHeader("Cache-Control", "no-cache");
		httpResponse.addHeader("Pragma", "no-cache");
		httpResponse.addHeader("Expires", "-1");
	}

	static Period getPeriod(HttpServletRequest req) {
		final Period period;
		if (req.getParameter(MonitoringController.PERIOD_PARAMETER) == null) {
			period = Period.JOUR;
		} else {
			period = Period.valueOfIgnoreCase(req
					.getParameter(MonitoringController.PERIOD_PARAMETER));
		}
		return period;
	}

	private void doCompressedHtml(HttpServletRequest httpRequest, HttpServletResponse httpResponse,
			List<JavaInformations> javaInformationsList, Period period, String part)
			throws IOException {
		if (isCompressionSupported(httpRequest)) {
			// comme la page html peut être volumineuse avec toutes les requêtes sql et http
			// on compresse le flux de réponse en gzip (à moins que la compression http
			// ne soit pas supportée comme par ex s'il y a un proxy squid qui ne supporte que http 1.0)
			final CompressionServletResponseWrapper wrappedResponse = new CompressionServletResponseWrapper(
					httpResponse, 4096);
			try {
				doHtml(httpRequest, wrappedResponse, javaInformationsList, period, part);
			} finally {
				wrappedResponse.finishResponse();
			}
		} else {
			doHtml(httpRequest, httpResponse, javaInformationsList, period, part);
		}
	}

	private Serializable createSerializable(HttpServletRequest httpRequest,
			List<JavaInformations> javaInformationsList) {
		final String part = httpRequest.getParameter(PART_PARAMETER);
		if (HEAP_HISTO_PART.equalsIgnoreCase(part)) {
			// par sécurité
			Action.checkSystemActionsEnabled();
			try {
				return VirtualMachine.createHeapHistogram();
			} catch (final Exception e) {
				return e;
			}
		} else if (SESSIONS_PART.equalsIgnoreCase(part)) {
			// par sécurité
			Action.checkSystemActionsEnabled();
			final String sessionId = httpRequest.getParameter(SESSION_ID_PARAMETER);
			if (sessionId == null) {
				final List<SessionInformations> sessionsInformations = SessionListener
						.getAllSessionsInformations();
				return (Serializable) sessionsInformations;
			}
			final SessionInformations sessionInformations = SessionListener
					.getSessionInformationsBySessionId(sessionId);
			return sessionInformations;
		} else if (PROCESSES_PART.equalsIgnoreCase(part)) {
			// par sécurité
			Action.checkSystemActionsEnabled();
			try {
				return (Serializable) ProcessInformations.buildProcessInformations();
			} catch (final Exception e) {
				return e;
			}
		}

		final List<Counter> counters = collector.getCounters();
		final List<Serializable> serialized = new ArrayList<Serializable>(counters.size()
				+ javaInformationsList.size());
		// on clone les counters avant de les sérialiser pour ne pas avoir de problèmes de concurrences d'accès
		for (final Counter counter : counters) {
			serialized.add(counter.clone());
		}
		for (final JavaInformations javaInformations : javaInformationsList) {
			serialized.add(javaInformations);
		}
		return (Serializable) serialized;
	}

	private void doHtml(HttpServletRequest httpRequest, HttpServletResponse httpResponse,
			List<JavaInformations> javaInformationsList, Period period, String part)
			throws IOException {
		if (!collectorServer && !SESSIONS_PART.equalsIgnoreCase(part)
				&& !HEAP_HISTO_PART.equalsIgnoreCase(part)) {
			// avant de faire l'affichage on fait une collecte,  pour que les courbes
			// et les compteurs par jour soit à jour avec les dernières requêtes
			collector.collectLocalContextWithoutErrors();
		}

		// simple appel de monitoring sans format
		httpResponse.setContentType(HTML_CONTENT_TYPE);
		final BufferedWriter writer = new BufferedWriter(httpResponse.getWriter());
		try {
			final HtmlReport htmlReport = new HtmlReport(collector, collectorServer,
					javaInformationsList, period, writer);
			if (part == null) {
				htmlReport.toHtml(messageForReport);
			} else {
				doHtmlPart(httpRequest, part, htmlReport);
			}
		} finally {
			writer.close();
		}
	}

	private void doHtmlPart(HttpServletRequest httpRequest, String part, HtmlReport htmlReport)
			throws IOException {
		if ("graph".equalsIgnoreCase(part)) {
			final String graphName = httpRequest.getParameter(GRAPH_PARAMETER);
			htmlReport.writeRequestAndGraphDetail(graphName);
		} else if (SESSIONS_PART.equalsIgnoreCase(part)) {
			doSessions(httpRequest, htmlReport);
		} else if (!collectorServer && CURRENT_REQUESTS_PART.equalsIgnoreCase(part)) {
			doCurrentRequests(htmlReport);
		} else if (HEAP_HISTO_PART.equalsIgnoreCase(part)) {
			doHeapHisto(htmlReport);
		} else if (PROCESSES_PART.equalsIgnoreCase(part)) {
			doProcesses(htmlReport);
		}
	}

	private void doSessions(HttpServletRequest httpRequest, HtmlReport htmlReport)
			throws IOException {
		// par sécurité
		Action.checkSystemActionsEnabled();
		List<SessionInformations> sessionsInformations = sessionsInformationsIfCollectServer;
		// sessionsInformations null si pas serveur de collecte
		assert sessionsInformations == null && !collectorServer || sessionsInformations != null
				&& collectorServer;
		final String sessionId = httpRequest.getParameter(SESSION_ID_PARAMETER);
		if (sessionId == null) {
			if (sessionsInformations == null) {
				sessionsInformations = SessionListener.getAllSessionsInformations();
			}
			htmlReport.writeSessions(sessionsInformations, messageForReport,
					MonitoringController.SESSIONS_PART);
		} else {
			final SessionInformations sessionInformation;
			if (sessionsInformations == null) {
				sessionInformation = SessionListener.getSessionInformationsBySessionId(sessionId);
			} else {
				sessionInformation = sessionsInformations.get(0);
			}
			htmlReport.writeSessionDetail(sessionId, sessionInformation);
		}
	}

	private void doCurrentRequests(HtmlReport htmlReport) throws IOException {
		assert !collectorServer;
		htmlReport.writeCurrentRequests(JavaInformations.buildThreadInformationsList(), true,
				new HashMap<String, HtmlCounterReport>());
	}

	private void doHeapHisto(HtmlReport htmlReport) throws IOException {
		// par sécurité
		Action.checkSystemActionsEnabled();
		HeapHistogram heapHistogram = heapHistogramIfCollectServer;
		// heapHistogram null si pas serveur de collecte
		assert heapHistogram == null && !collectorServer || heapHistogram != null
				&& collectorServer;
		if (heapHistogram == null) {
			try {
				heapHistogram = VirtualMachine.createHeapHistogram();
			} catch (final Exception e) {
				Collector.printStackTrace(e);
				htmlReport.writeMessageIfNotNull(String.valueOf(e.getMessage()), null);
				return;
			}
		}
		htmlReport.writeHeapHistogram(heapHistogram, messageForReport,
				MonitoringController.HEAP_HISTO_PART);
	}

	private void doProcesses(HtmlReport htmlReport) throws IOException {
		// par sécurité
		Action.checkSystemActionsEnabled();
		try {
			htmlReport.writeProcesses(ProcessInformations.buildProcessInformations());
		} catch (final Exception e) {
			Collector.printStackTrace(e);
			htmlReport.writeMessageIfNotNull(String.valueOf(e.getMessage()), null);
		}
	}

	void writeHtmlToLastShutdownFile() {
		try {
			final File dir = Parameters.getStorageDirectory(collector.getApplication());
			final File lastShutdownFile = new File(dir, "last_shutdown.html");
			final BufferedWriter writer = new BufferedWriter(new FileWriter(lastShutdownFile));
			try {
				final JavaInformations javaInformations = new JavaInformations(Parameters
						.getServletContext(), true);
				// on pourrait faire I18N.bindLocale(Locale.getDefault()), mais cela se fera tout seul
				final HtmlReport htmlReport = new HtmlReport(collector, collectorServer,
						Collections.singletonList(javaInformations), Period.JOUR, writer);
				htmlReport.toHtml(null);
			} finally {
				writer.close();
			}
		} catch (final IOException e) {
			Collector.printStackTrace(e);
		}
	}

	private void doPdf(HttpServletRequest httpRequest, HttpServletResponse httpResponse,
			Period period, List<JavaInformations> javaInformationsList) throws IOException {
		if (!collectorServer) {
			// avant de faire l'affichage on fait une collecte,  pour que les courbes
			// et les compteurs par jour soit à jour avec les dernières requêtes
			collector.collectLocalContextWithoutErrors();
		}

		// simple appel de monitoring sans format
		httpResponse.setContentType("application/pdf");
		httpResponse.addHeader("Content-Disposition", encodeFileNameToContentDisposition(
				httpRequest, PdfReport.getFileName(collector.getApplication())));
		try {
			final PdfReport pdfReport = new PdfReport(collector, collectorServer,
					javaInformationsList, period, httpResponse.getOutputStream());
			pdfReport.toPdf();
		} finally {
			httpResponse.getOutputStream().flush();
		}
	}

	private void doResource(HttpServletResponse httpResponse, String resource) throws IOException {
		httpResponse.addHeader("Cache-Control", "max-age=3600"); // cache navigateur 1h
		final OutputStream out = httpResponse.getOutputStream();
		// on enlève tout ".." dans le paramètre par sécurité
		final String localResource = Parameters.getResourcePath(resource.replace("..", ""));
		// ce contentType est nécessaire sinon la css n'est pas prise en compte
		// sous firefox sur un serveur distant
		httpResponse.setContentType(Parameters.getServletContext().getMimeType(localResource));
		final InputStream in = new BufferedInputStream(getClass()
				.getResourceAsStream(localResource));
		try {
			TransportFormat.pump(in, out);
		} finally {
			in.close();
		}
	}

	private void doGraph(HttpServletRequest httpRequest, HttpServletResponse httpResponse,
			Period period, String graphName) throws IOException {
		final int width = Math.min(Integer.parseInt(httpRequest.getParameter(WIDTH_PARAMETER)),
				1600);
		final int height = Math.min(Integer.parseInt(httpRequest.getParameter(HEIGHT_PARAMETER)),
				1600);
		final JRobin jrobin = collector.getJRobin(graphName);
		if (jrobin != null) {
			final byte[] img = jrobin.graph(period, width, height);
			// png comme indiqué dans la classe jrobin
			httpResponse.setContentType("image/png");
			httpResponse.setContentLength(img.length);
			final String fileName = graphName + ".png";
			httpResponse.addHeader("Content-Disposition", "inline;filename=" + fileName);
			httpResponse.getOutputStream().write(img);
			httpResponse.flushBuffer();
		}
	}

	private void doWebXml(HttpServletResponse httpResponse) throws IOException {
		// par sécurité
		Action.checkSystemActionsEnabled();
		final OutputStream out = httpResponse.getOutputStream();
		httpResponse.setContentType(Parameters.getServletContext().getMimeType("/WEB-INF/web.xml"));
		httpResponse.addHeader("Content-Disposition", "inline;filename=web.xml");
		final InputStream in = getWebXmlAsStream();
		if (in != null) {
			try {
				TransportFormat.pump(in, out);
			} finally {
				in.close();
			}
		}
	}

	private void doPomXml(HttpServletResponse httpResponse) throws IOException {
		// par sécurité
		Action.checkSystemActionsEnabled();
		final OutputStream out = httpResponse.getOutputStream();
		httpResponse.setContentType(Parameters.getServletContext().getMimeType("/WEB-INF/web.xml"));
		httpResponse.addHeader("Content-Disposition", "inline;filename=pom.xml");
		final InputStream in = getPomXmlAsStream();
		if (in != null) {
			try {
				TransportFormat.pump(in, out);
			} finally {
				in.close();
			}
		}
	}

	private static InputStream getWebXmlAsStream() {
		final InputStream webXml = Parameters.getServletContext().getResourceAsStream(
				"/WEB-INF/web.xml");
		if (webXml == null) {
			return null;
		}
		return new BufferedInputStream(webXml);
	}

	@SuppressWarnings("unchecked")
	private static InputStream getPomXmlAsStream() {
		final ServletContext servletContext = Parameters.getServletContext();
		final Set mavenDir = servletContext.getResourcePaths("/META-INF/maven/");
		if (mavenDir == null || mavenDir.isEmpty()) {
			return null;
		}
		final Set groupDir = servletContext.getResourcePaths((String) mavenDir.iterator().next());
		if (groupDir == null || groupDir.isEmpty()) {
			return null;
		}
		final InputStream pomXml = servletContext.getResourceAsStream(groupDir.iterator().next()
				+ "pom.xml");
		if (pomXml == null) {
			return null;
		}
		return new BufferedInputStream(pomXml);
	}

	/**
	 * Encode un nom de fichier avec des % pour Content-Disposition, avec téléchargement.
	 * (US-ASCII + Encode-Word : http://www.ietf.org/rfc/rfc2183.txt, http://www.ietf.org/rfc/rfc2231.txt
	 * sauf en MS IE qui ne supporte pas cet encodage et qui n'en a pas besoin)
	 * @param httpRequest HttpServletRequest
	 * @param fileName String
	 * @return String
	 */
	private static String encodeFileNameToContentDisposition(HttpServletRequest httpRequest,
			String fileName) {
		assert fileName != null;
		final String userAgent = httpRequest.getHeader("user-agent");
		if (userAgent != null && userAgent.contains("MSIE")) {
			return "attachment;filename=" + fileName;
		}
		return encodeFileNameToStandardContentDisposition(fileName);
	}

	private static String encodeFileNameToStandardContentDisposition(String fileName) {
		final int length = fileName.length();
		final StringBuilder sb = new StringBuilder(length + length / 4);
		// attachment et non inline pour proposer l'enregistrement (sauf IE6)
		// et non l'affichage direct dans le navigateur
		sb.append("attachment;filename*=\"");
		char c;
		for (int i = 0; i < length; i++) {
			c = fileName.charAt(i);
			if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
				sb.append(c);
			} else {
				sb.append('%');
				if (c < 16) {
					sb.append('0');
				}
				sb.append(Integer.toHexString(c));
			}
		}
		sb.append('"');
		return sb.toString();
	}

	private static boolean isCompressionSupported(HttpServletRequest httpRequest) {
		// est-ce que le navigateur déclare accepter la compression gzip ?
		boolean supportCompression = false;
		@SuppressWarnings("unchecked")
		final List<String> acceptEncodings = Collections.list(httpRequest
				.getHeaders("Accept-Encoding"));
		for (final String name : acceptEncodings) {
			if (name.contains("gzip")) {
				supportCompression = true;
				break;
			}
		}
		return supportCompression;
	}

	void setHeapHistogramIfCollectServer(HeapHistogram heapHistogram) {
		assert collectorServer;
		this.heapHistogramIfCollectServer = heapHistogram;
	}

	void setSessionsInformations(List<SessionInformations> sessionsInformations) {
		assert collectorServer;
		this.sessionsInformationsIfCollectServer = sessionsInformations;
	}
}
