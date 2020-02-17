package controllers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
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
	
	private final String sqlTemplate;
	
	@Inject
	public Index(Configuration config) {
		timeZone = config.getString("app.timezone");
		Logger.info("time zone set for app is: " + timeZone);
		
		whereClause = config.getString("sql.whereClause");
		if(whereClause != null) whereClause = whereClause.trim();
		
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
		
		sqlTemplate = sqlTemplateBuilder.toString();
		
		final Runnable removeFiles = () -> {
			try {
				removeRedundantFiles();
			} catch(Exception e) {
				e.printStackTrace();
			}
		};
		
		final ScheduledFuture<?> removeFilesHandle =
				Executors.newScheduledThreadPool(1)
					.scheduleAtFixedRate(removeFiles, 1, 10, TimeUnit.MINUTES);
	}
	
	private void removeRedundantFiles() {
		Logger.info("checking redundant files...");
		Path list = Paths.get("/opt/csvs");
		try {
			Files.list(list)
				.filter(path -> checkAgeOfFile(path))
				.forEach(path -> removeRedundantFile(path));
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	private boolean checkAgeOfFile(Path path) {
		try {
			ZonedDateTime fileTime = ZonedDateTime.ofInstant(
					Files.getLastModifiedTime(path).toInstant(), ZoneId.of(timeZone));
			
			ZonedDateTime now = ZonedDateTime.of(LocalDateTime.now(), ZoneId.of(timeZone));
			
			Duration d = Duration.between(fileTime, now);
			long ageOfFileInMinutes = d.toMinutes();
			
			Logger.info("file " + path.getFileName() + " is " + ageOfFileInMinutes + " minutes old");
			
			return ageOfFileInMinutes > 15;
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
	
	public Result index() {
		LocalDateTime now = LocalDateTime.now();
		String dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
		
		String whereParam = request().getQueryString("where");
		
		String sql = sqlTemplate;
		if(whereParam == null && whereClause != null) sql = sqlTemplate.replace(whereClause, "");
		
		String uuid = UUID.randomUUID().toString();
		String filePathString = "/opt/csvs/" + uuid + ".csv";
		Logger.info("path of file to be written: " + filePathString);
		
		try(
			Connection connection = DB.getConnection(false);
			PreparedStatement stmt = connection.prepareStatement(sql);
			BufferedWriter writer = new BufferedWriter(new FileWriter(filePathString));
		) {
			if(whereParam != null && whereClause != null) stmt.setString(1, whereParam);
			
			stmt.setFetchSize(100000);
			
			try(ResultSet rs = stmt.executeQuery()) {
				handleResultSet(rs, writer);
			}
		} catch(SQLException sqle) {
			sqle.printStackTrace();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
		
		response().setContentType("text/csv; charset=utf-8");
		response().setHeader(
				"Content-Disposition", "attachment; filename=\"" + 
				filenamePrefix + dateTime + ".csv\"");
		
		return ok(new File(filePathString)).as("UTF-8").as("text/csv");
	}
	
	private void handleResultSet(ResultSet rs, BufferedWriter writer) throws SQLException, IOException {
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
}
