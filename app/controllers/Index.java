package controllers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import play.Configuration;
import play.Logger;
import play.db.DB;
import play.mvc.Controller;
import play.mvc.Result;

public class Index extends Controller {
	private final String timeZone;
	
	private String whereClause;
	
	private final String filenamePrefix;
	
	private final boolean isRequestExtraInfo;
	
	private final String sqlTemplate;
	
	private final String pythonCommand;
	
	private final String pythonFile;
	
	@Inject
	public Index(Configuration config) {
		timeZone = config.getString("app.timezone");
		Logger.info("time zone set for app is: " + timeZone);
		
		whereClause = config.getString("sql.whereClause");
		if(whereClause != null) whereClause = whereClause.trim();
		
		Logger.info("whereClause: " + whereClause);
		
		filenamePrefix = config.getString("output.filenamePrefix");
		String sqlFile = config.getString("sql.file");
		
		Logger.info("Using configuration: filenamePrefix=" + filenamePrefix + ", sqlFile=" + sqlFile);
		
		StringBuilder sqlTemplateBuilder = new StringBuilder();
		
		try(BufferedReader br = new BufferedReader(new FileReader(sqlFile))) {
			String line;
			while ((line = br.readLine()) != null) {
				if(line.contains("%WHERE_CLAUSE%")) line = line.replace("%WHERE_CLAUSE%", whereClause);
				
				sqlTemplateBuilder
					.append(" ")
					.append(line.trim());
			}
		} catch(RuntimeException e) {
			throw e;
		} catch(Exception e) {
			throw new RuntimeException("couldn't read sql file", e);
		}
		
		isRequestExtraInfo = Boolean.parseBoolean(config.getString("report.isrequestextrainfo"));
		Logger.info("isRequestExtraInfo: " + isRequestExtraInfo);
		
		sqlTemplate = sqlTemplateBuilder.toString();
		
		final Runnable removeFiles = () -> {
			try {
				removeRedundantFiles();
			} catch(Exception e) {
				e.printStackTrace();
			}
		};
		
		pythonCommand = config.getString("report.python.command");
		pythonFile = config.getString("report.python.file");
		
		Logger.info("pythonCommand is: " + pythonCommand);
		Logger.info("pythonFile is: " + pythonFile);
		
		Executors.newScheduledThreadPool(1).scheduleAtFixedRate(removeFiles, 1, 30, TimeUnit.MINUTES);
	}
	
	public Result index() {
		LocalDateTime now = LocalDateTime.now();
		String dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
		
		String dateStartParam = request().getQueryString("date_start");
		String dateEndParam = request().getQueryString("date_end");
		
		Logger.info("the date start param is: " + dateStartParam);
		Logger.info("the date end param is: " + dateEndParam);
		
		String sql = sqlTemplate;
		if((dateStartParam == null || dateEndParam == null) && whereClause != null) {
			sql = sqlTemplate.replace(whereClause, "");
		}
		
		String uuid = UUID.randomUUID().toString();
		String filePathString = "/opt/csvs/" + uuid + ".csv";
		Logger.info("path of file to be written: " + filePathString);
		
		try(
			Connection connection = DB.getConnection(false);
			PreparedStatement stmt = connection.prepareStatement(sql);
			BufferedWriter writer = new BufferedWriter(new FileWriter(filePathString));
		) {
			Logger.info("auto commit: " + connection.getAutoCommit());
			
			if(dateStartParam != null && dateEndParam != null && whereClause != null) {
				stmt.setTimestamp(1, convertStringToTimestamp(dateStartParam, false));
				stmt.setTimestamp(2, convertStringToTimestamp(dateEndParam, true));
			}
			
			stmt.setFetchSize(5000);
			
			Logger.info("executing query for: " + uuid);
			Logger.info("free memory before query: " + Runtime.getRuntime().freeMemory());
			try(ResultSet rs = stmt.executeQuery()) {
				Logger.info("free memory after query: " + Runtime.getRuntime().freeMemory());
				handleResultSet(rs, writer, uuid);
			}
		} catch(IOException ioe) {
			ioe.printStackTrace();
		} catch(SQLException sqle) {
			sqle.printStackTrace();
		} catch(RuntimeException re) {
			re.printStackTrace();
		} catch(OutOfMemoryError oome) {
			oome.printStackTrace();
		}
		
		response().setContentType("text/csv; charset=utf-8");
		response().setHeader(
				"Content-Disposition", "attachment; filename=\"" + 
				filenamePrefix + dateTime + ".csv\"");
		
		if(isRequestExtraInfo) {
			Logger.info("removing double rows");
			String filePathCondensedString = "/opt/csvs/" + uuid + "_condensed.csv";
			
			runPythonScript(filePathString, filePathCondensedString);
			
			return ok(new File(filePathCondensedString)).as("UTF-8").as("text/csv");
		}
		
		Logger.info("returning file: " + uuid);
		
		return ok(new File(filePathString)).as("UTF-8").as("text/csv");
	}
	
	private void runPythonScript(String inputFile, String outputFile) {
		try {
			String pythonExec = 
					pythonCommand
						.concat(" ")
						.concat(pythonFile)
						.concat(" ")
						.concat(inputFile)
						.concat(" ")
						.concat(outputFile);
			
			Logger.info("python exec: {}", pythonExec);
			
			Process process = Runtime.getRuntime().exec(pythonExec);
			InputStream inputStream = process.getInputStream();
			String inputContent = getInputStreamContent(inputStream);
			if(inputContent != null && inputContent.trim().length() > 0) {
				Logger.info("input is: {}", inputContent);
			}
			
			InputStream errorStream = process.getErrorStream();
			String errorContent = getInputStreamContent(errorStream);
			if(errorContent != null && errorContent.trim().length() > 0) {
				Logger.info("error is: {}", errorContent);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private String getInputStreamContent(InputStream inputStream) {
		int bufferSize = 1024;
		char[] buffer = new char[bufferSize];
		StringBuilder out = new StringBuilder();
		
		try(Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8);) {
			for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
				out.append(buffer, 0, numRead);
			}
		} catch (IOException ioe) {
			ioe.printStackTrace();
		} finally {
			try {
				inputStream.close();
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		}
		
		return out.toString();
	}
	
	private void handleResultSet(ResultSet rs, BufferedWriter writer, String uuid) throws SQLException, IOException {
		Logger.info("writing file: " + uuid);
		Logger.info("free memory before writing: " + Runtime.getRuntime().freeMemory());
		ResultSetMetaData rsm = rs.getMetaData();
		for(Integer i = 1; i < rsm.getColumnCount() + 1; i++) {
			writer.write("\"" + rsm.getColumnName(i) + "\";");
		}
		
		writer.write(System.lineSeparator());
		
		while(rs.next()) {	
			for(Integer i = 1; i < rsm.getColumnCount() + 1; i++) {
				Object o = rs.getObject(i);
				writeLine(o, writer);
			}
			
			writer.write(System.lineSeparator());
		}
	}
	
	private void writeLine(Object o, BufferedWriter writer) throws IOException {
		if(o instanceof String) {
			String text = (String) o;
			
			if(text.endsWith("\"")) {
				text = text + "\"";
			}
			
			text = "\"" + text.replaceAll("[\\t\\n\\r]", " ") + "\";";
			writer.write(text);
		} else if(o == null) {
			String text = "\"" + "" + "\";";
			writer.write(text);
		} else {
			String text = "\"" + o + "\";";
			writer.write(text);
		}
	}
	
	private Timestamp convertStringToTimestamp(String dateAsString, boolean endDate) {
		LocalDate parsedDate = LocalDate.parse(dateAsString, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
		LocalDate actualDate = endDate ? parsedDate.plusDays(1) : parsedDate;
		return Timestamp.valueOf(actualDate.atStartOfDay());
	}
	
	private void removeRedundantFiles() {
		Logger.info("checking redundant files...");
		Path list = Paths.get("/opt/csvs");
		try {
			Files.list(list)
				.filter(path -> fileShouldBeRemoved(path))
				.forEach(path -> removeRedundantFile(path));
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	private boolean fileShouldBeRemoved(Path path) {
		try {
			ZonedDateTime fileTime = ZonedDateTime.ofInstant(
					Files.getLastModifiedTime(path).toInstant(), ZoneId.of(timeZone));
			
			ZonedDateTime now = ZonedDateTime.of(LocalDateTime.now(), ZoneId.of(timeZone));
			
			Duration d = Duration.between(fileTime, now);
			long ageOfFileInMinutes = d.toMinutes();
			
			Logger.info("file " + path.getFileName() + " is " + ageOfFileInMinutes + " minutes old");
			
			return ageOfFileInMinutes > 60;
		} catch (IOException ioe) {
			ioe.printStackTrace();
			return false;
		}
	}
	
	private void removeRedundantFile(Path path) {
		try {
			Logger.info("file to be removed: " + path.getFileName());
			
			Files.delete(path);
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
}
