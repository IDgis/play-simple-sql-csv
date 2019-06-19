package controllers;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.inject.Inject;

import play.Configuration;
import play.Logger;
import play.db.DB;
import play.mvc.Controller;
import play.mvc.Result;

public class Index extends Controller {
	
	private final String sql;
	
	private final String filenamePrefix;
	
	@Inject
	public Index(Configuration config) {
		filenamePrefix = config.getString("output.filenamePrefix");	
		String sqlFile = config.getString("sql.file");
		
		Logger.info("Using configuration: filenamePrefix=" + filenamePrefix + ", sqlFile=" + sqlFile);
		
		StringBuilder sqlBuilder = new StringBuilder();
		
		try(BufferedReader br = new BufferedReader(new FileReader(sqlFile))) {
			String line;
			while ((line = br.readLine()) != null) {
				sqlBuilder
					.append(" ")
					.append(line.trim());
			}
		} catch(RuntimeException e) {
			throw e;
		} catch(Exception e) {
			throw new RuntimeException("couldn't read sql file", e);
		}
		
		sql = sqlBuilder.toString();
	}

	public Result index() {
		LocalDateTime ldt = LocalDateTime.now();
		String dateTime = ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
		
		response().setContentType("text/csv; charset=utf-8");
		response().setHeader(
				"Content-Disposition", "attachment; filename=\"" + 
				filenamePrefix + dateTime + ".csv\"");
		
		ByteArrayOutputStream baos = null;
		
		try(
			Connection connection = DB.getConnection(false);
			PreparedStatement stmt = connection.prepareStatement(sql);
		) {
			try(
				ResultSet rs = stmt.executeQuery();	
			) {
				baos = new ByteArrayOutputStream();
				
				StringBuilder header = new StringBuilder();
				ResultSetMetaData rsm = rs.getMetaData();
				for(Integer i = 1; i < rsm.getColumnCount() + 1; i++) {
					header.append("\"" + rsm.getColumnName(i) + "\";");
				}
				
				baos.write(StandardCharsets.UTF_8.encode(header.toString()).array());
				baos.write(System.lineSeparator().getBytes());
				
				while(rs.next()) {	
					StringBuilder line = new StringBuilder();
					
					for(Integer i = 1; i < rsm.getColumnCount() + 1; i++) {
						Object o = rs.getObject(i);
						if(o instanceof String) {
							String str = (String) o;
							String finalStr = "";
							
							if(str.endsWith("\"")) {
								finalStr = str + "\"";
							} else {
								finalStr = str;
							}
							
							line.append("\"" + finalStr.replaceAll("[\\t\\n\\r]", " ") + "\";");
						} else if(o == null) {
							line.append("\"" + "" + "\";");
						} else {
							line.append("\"" + o + "\";");
						}
					}
					
					baos.write(StandardCharsets.UTF_8.encode(line.toString()).array());
					baos.write(System.lineSeparator().getBytes());
				}
			} finally {
				if(baos != null) baos.close();
			}
		} catch(IOException ioe) {
			ioe.printStackTrace();
		} catch(SQLException sqle) {
			sqle.printStackTrace();
		}
		
		if(baos == null) return internalServerError();
		
		ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
		return ok(bais).as("UTF-8").as("text/csv");
	}
}
