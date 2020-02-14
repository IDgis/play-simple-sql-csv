package controllers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import javax.inject.Inject;

import play.Configuration;
import play.Logger;
import play.db.DB;
import play.mvc.Controller;
import play.mvc.Result;

public class Index extends Controller {
	
	private String whereClause;
	
	private final String filenamePrefix;
	
	private final String sqlTemplate;
	
	@Inject
	public Index(Configuration config) {
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
	}
	
	public Result index() {
		LocalDateTime now = LocalDateTime.now();
		String dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
		
		String whereParam = request().getQueryString("where");
		
		String sql = sqlTemplate;
		if(whereParam == null && whereClause != null) sql = sqlTemplate.replace(whereClause, "");
		
		String uuid = UUID.randomUUID().toString();
		String filePathString = "/opt/csvs/" + uuid + ".csv";
		
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
