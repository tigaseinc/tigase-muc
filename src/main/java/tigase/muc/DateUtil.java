package tigase.muc;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

public class DateUtil {

	private final static SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

	static {
		FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	public static String formatDatetime(Date date) {
		Calendar now = Calendar.getInstance();
		now.setTimeZone(TimeZone.getTimeZone("GMT"));
		now.setTime(date);
		return String.format("%1$tY-%1$tm-%1$tdT%1$tH:%1$tM:%1$tSZ", now);
	}

	/**
	 * Used only in jabber:x:delivery
	 * 
	 * @param date
	 * @return
	 */
	public static String formatOld(final Date date) {
		Calendar now = Calendar.getInstance();
		now.setTimeZone(TimeZone.getTimeZone("GMT"));
		now.setTime(date);
		return String.format("%1$tY%1$tm%1$tdT%1$tH:%1$tM:%1$tS", now);
	}

	public static Date parse(String s) {
		try {
			return FORMAT.parse(s);
		} catch (Exception e) {
			return null;
		}
	}

	private DateUtil() {
	}
}
