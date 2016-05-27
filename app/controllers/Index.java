package controllers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

import org.joda.time.LocalDate;

import play.db.DB;
import play.mvc.Controller;
import play.mvc.Result;

public class Index extends Controller {

	public Result index() throws Exception {
		LocalDate ld = LocalDate.now();
		
		response().setContentType("text/csv");
		response().setHeader("Content-Disposition", "attachment; filename=\"rapport_geodata_" + ld.getYear() + ld.getMonthOfYear() + 
				ld.getDayOfMonth() + ".csv\"");
		
		StringBuilder sql = new StringBuilder();
		StringBuilder csv = new StringBuilder();
		String line;
		
		InputStream input = null;
		if("dataset".equals(play.Play.application().configuration().getString("sql.type"))) {
			input = getClass().getClassLoader().getResourceAsStream("dataset.sql");
		} else if("download".equals(play.Play.application().configuration().getString("sql.type"))) {
			input = getClass().getClassLoader().getResourceAsStream("download.sql");
		}
		
		if(input != null) {
			try(
				BufferedReader br = new BufferedReader(new InputStreamReader(input));
			) {
				while ((line = br.readLine()) != null) {
					sql.append(line);
				}
			} catch(Exception e) {
				e.printStackTrace();
			}
			
			try(
				Connection connection = DB.getConnection(false);
				PreparedStatement stmt = connection.prepareStatement(sql.toString());	
			) {
				try(
					ResultSet rs = stmt.executeQuery();	
				) {
					ResultSetMetaData rsm = rs.getMetaData();
					for(Integer i = 1; i < rsm.getColumnCount() + 1; i++) {
						csv.append("\"" + rsm.getColumnName(i) + "\";");
					}
					
					csv.append(System.lineSeparator());
					
					while(rs.next()) {	
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
								
								csv.append("\"" + finalStr.replaceAll("[\\t\\n\\r]", " ") + "\";");
							} else if(o == null) {
								csv.append("\"" + "" + "\";");
							} else {
								csv.append("\"" + o + "\";");
							}
						}
						
						csv.append(System.lineSeparator());
					}
				}
			}
		}
		
		return ok(csv.toString().getBytes());
	}
}
