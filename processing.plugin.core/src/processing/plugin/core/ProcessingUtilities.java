/**
 * Copyright (c) 2010 Ben Fry and Casey Reas
 *
 * This program and the accompanying materials are made available under the 
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.opensource.org/licenses/eclipse-1.0.php
 */
package processing.plugin.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.resources.IFile;
import processing.core.PConstants;

/**
 * A static class containing some of the static utility methods from PApplet and 
 * processing.app.Base, as well as some similar methods for operating on IResource 
 * classes in the Eclipse workspace. This is not an exhaustive port.
 * <p>
 * Code Duplication is generally to be avoided, but these two classes cannot be reused
 * without invoking Swing or other UI, which should be avoided in the plugin.core code.
 * <p>
 * Some of these methods rely on File.io stuff, so if you're using them you may need to 
 * use <code>ProjectName.refreshLocal(IResource.DEPTH_INFINITE, monitor);</code> the
 * workspace back into sync. Be aware, this is can be a costly operation for large 
 * sketches on some file systems.
 */
public class ProcessingUtilities implements PConstants{

	 /**
	   * Current platform in use, one of the
	   * PConstants WINDOWS, MACOSX, MACOS9, LINUX or OTHER.
	   */
	  static public int platform;

	  static {
	    String osname = System.getProperty("os.name");

	    if (osname.indexOf("Mac") != -1) {
	      platform = MACOSX;

	    } else if (osname.indexOf("Windows") != -1) {
	      platform = WINDOWS;

	    } else if (osname.equals("Linux")) {  // true for the ibm vm
	      platform = LINUX;

	    } else {
	      platform = OTHER;
	    }
	  }
	
	
	/** This class is not meant to be instantiated. */
	private ProcessingUtilities(){}

	// . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .
	// PLUG-IN UTILITIES

	public static final String PACKAGE_REGEX = "(?:^|\\s|;)package\\s+(\\S+)\\;";
	public static final String SIZE_REGEX = 
		"(?:^|\\s|;)size\\s*\\(\\s*(\\S+)\\s*,\\s*([^\\s,\\)]+),?\\s*([^\\)]*)\\s*\\)\\s*\\;";

	/**
	 * Read in a file and return it as a string
	 * 
	 * @param file a resource handler for a file
	 * @return contents of the file
	 */
	public static String readFile(IFile file) {
		if (!file.exists())
			return "";
		InputStream stream = null;
		try{
			stream = file.getContents();
			Reader reader = new BufferedReader(new InputStreamReader(stream));
			StringBuffer result = new StringBuffer(2048);
			char[] buf = new char[2048];
			while (true){
				int count = reader.read(buf);
				if (count < 0)
					break;
				result.append(buf, 0, count);
			}
			return result.toString();
		} catch (Exception e){ // IOException and CoreException
			ProcessingCore.logError(e);
			return "";
		} finally {
			try{
				if (stream != null)
					stream.close();
			} catch (IOException e){
				ProcessingCore.logError(e);
				return "";
			}
		}
	}

	/**
	 * Count newlines in a string
	 * 
	 * @param what
	 * @return number of newline statements
	 */
	public static int getLineCount(String what){
		int count = 1;
		for (char c : what.toCharArray()) {
			if (c == '\n') count++;
		}
		return count;
	}

	/**
	 * Given a folder, return a list of absolute paths to all .jar and .zip
	 * (but not .class) files inside that folder, separated by the system's
	 * path separator character.
	 * <p>
	 * This will prepend the system's path separator so that it can be directly 
	 * appended to another path string.
	 * <p>
	 * This function doesn't bother checking to see if there are any .class
	 * files in the folder or within a subfolder.
	 */
	static public String contentsToClassPath(File folder) { 
		if (folder == null) return "";
		if (!folder.isDirectory()) return "";

		StringBuffer abuffer = new StringBuffer();
		String sep = System.getProperty("path.separator");

		try {
			String path = folder.getCanonicalPath();

			// When getting the name of this folder, make sure it has a slash
			// after it, so that the names of sub-items can be added.
			if (!path.endsWith(File.separator)) path += File.separator;

			String list[] = folder.list();

			for (int i = 0; i < list.length; i++) {
				// Skip . and ._ files. Prior to 0125p3, .jar files that had
				// OS X AppleDouble files associated would cause trouble.
				if (list[i].startsWith(".")) continue;

				if (list[i].toLowerCase().endsWith(".jar") ||
						list[i].toLowerCase().endsWith(".zip")) {
					abuffer.append(sep);
					abuffer.append(path);
					abuffer.append(list[i]);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();  // this would be odd
		}
		return abuffer.toString();
	}

	/**
	 * A classpath, separated by the path separator, will contain
	 * a series of .jar/.zip files or directories containing .class
	 * files, or containing subdirectories that have .class files.
	 *
	 * @param path the input classpath
	 * @return array of possible package names
	 */
	static public String[] packageListFromClassPath(String path) {
		Hashtable table = new Hashtable();
		String pieces[] =
			split(path, File.pathSeparatorChar);

		for (int i = 0; i < pieces.length; i++) {
			//System.out.println("checking piece '" + pieces[i] + "'");
			if (pieces[i].length() == 0) continue;

			if (pieces[i].toLowerCase().endsWith(".jar") ||
					pieces[i].toLowerCase().endsWith(".zip")) {
				//System.out.println("checking " + pieces[i]);
				packageListFromZip(pieces[i], table);

			} else {  // it's another type of file or directory
				File dir = new File(pieces[i]);
				if (dir.exists() && dir.isDirectory()) {
					packageListFromFolder(dir, null, table);
				}
			}
		}
		int tableCount = table.size();
		String output[] = new String[tableCount];
		int index = 0;
		Enumeration e = table.keys();
		while (e.hasMoreElements()) {
			output[index++] = ((String) e.nextElement()).replace('/', '.');
		}
		return output;
	}

	/**
	 * Get a list of packages contained in a .zip file
	 */
	static public void packageListFromZip(String filename, Hashtable table) {
		try {
			ZipFile file = new ZipFile(filename);
			Enumeration entries = file.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = (ZipEntry) entries.nextElement();

				if (!entry.isDirectory()) {
					String name = entry.getName();

					if (name.endsWith(".class")) {
						int slash = name.lastIndexOf('/');
						if (slash == -1) continue;

						String pname = name.substring(0, slash);
						if (table.get(pname) == null) {
							table.put(pname, new Object());
						}
					}
				}
			}
		} catch (IOException e) {
			ProcessingCore.logInfo("Ignoring " + filename + " (" + e.getMessage() + ")");
		}
	}

	/**
	 * Make list of package names by traversing a directory hierarchy. Each time a 
	 * class is found in a folder, add its containing set of folders to the package 
	 * list. If another folder is found, walk down into that folder and continue.
	 */
	static public void packageListFromFolder(File dir, String sofar, Hashtable table) {

		boolean foundClass = false;
		String files[] = dir.list();

		for (int i = 0; i < files.length; i++) {
			if (files[i].equals(".") || files[i].equals("..")) continue;

			File sub = new File(dir, files[i]);
			if (sub.isDirectory()) {
				String nowfar =
					(sofar == null) ? files[i] : (sofar + "." + files[i]);
					packageListFromFolder(sub, nowfar, table);
			} else if (!foundClass) {  // if no classes found in this folder yet
				if (files[i].endsWith(".class")) {
					table.put(sofar, new Object());
					foundClass = true;
				}
			}
		}
	}

	/**
	 * Produce a sanitized name that fits our standards for likely to work.
	 * <p/>
	 * Java classes have a wider range of names that are technically allowed
	 * (supposedly any Unicode name) than what we support. The reason for
	 * going more narrow is to avoid situations with text encodings and
	 * converting during the process of moving files between operating
	 * systems, i.e. uploading from a Windows machine to a Linux server,
	 * or reading a FAT32 partition in OS X and using a thumb drive.
	 * <p/>
	 * This helper function replaces everything but A-Z, a-z, and 0-9 with
	 * underscores. Also disallows starting the sketch name with a digit.
	 */
	static public String sanitizeName(String origName) {
		char c[] = origName.toCharArray();
		StringBuffer buffer = new StringBuffer();

		// can't lead with a digit, so start with an underscore
		if ((c[0] >= '0') && (c[0] <= '9')) {
			buffer.append('_');
		}
		for (int i = 0; i < c.length; i++) {
			if (((c[i] >= '0') && (c[i] <= '9')) ||
					((c[i] >= 'a') && (c[i] <= 'z')) ||
					((c[i] >= 'A') && (c[i] <= 'Z'))) {
				buffer.append(c[i]);

			} else {
				buffer.append('_');
			}
		}
		// let's not be ridiculous about the length of filenames.
		// in fact, Mac OS 9 can handle 255 chars, though it can't really
		// deal with filenames longer than 31 chars in the Finder.
		// but limiting to that for sketches would mean setting the
		// upper-bound on the character limit here to 25 characters
		// (to handle the base name + ".class")
		if (buffer.length() > 63) {
			buffer.setLength(63);
		}
		return buffer.toString();
	}

	public static void deleteFolderContents(File folder) {
		if (folder == null) return;
		if (!folder.isDirectory()) return;
		for (File f : folder.listFiles()){
			if(!f.delete()){
				if(f.isDirectory()) {
					deleteFolderContents(f);
				} else {
					// that's odd. could not be deleted and isn't a directory. it might be locked or have permissions issues
					ProcessingCore.logError(	"Could not delete " + f.getName() 
							+ ". If it causes problems, you may have to manually delete it. You can find it here: " 
							+ f.getAbsolutePath(), null);
				}
			}
		}		
	}

	static public String scrubComments(String what) {
		char p[] = what.toCharArray();

		int index = 0;
		while (index < p.length) {
			// for any double slash comments, ignore until the end of the line
			if ((p[index] == '/') &&
					(index < p.length - 1) &&
					(p[index+1] == '/')) {
				p[index++] = ' ';
				p[index++] = ' ';
				while ((index < p.length) &&
						(p[index] != '\n')) {
					p[index++] = ' ';
				}

				// check to see if this is the start of a new multiline comment.
				// if it is, then make sure it's actually terminated somewhere.
			} else if ((p[index] == '/') &&
					(index < p.length - 1) &&
					(p[index+1] == '*')) {
				p[index++] = ' ';
				p[index++] = ' ';
				boolean endOfRainbow = false;
				while (index < p.length - 1) {
					if ((p[index] == '*') && (p[index+1] == '/')) {
						p[index++] = ' ';
						p[index++] = ' ';
						endOfRainbow = true;
						break;

					} else {
						// continue blanking this area
						p[index++] = ' ';
					}
				}
				if (!endOfRainbow) {
					throw new RuntimeException("Missing the */ from the end of a " +
					"/* comment */");
				}
			} else {  // any old character, move along
				index++;
			}
		}
		return new String(p);
	}

	// . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .

	//// PApplet ////

	//////////////////////////////////////////////////////////////

	// READERS AND WRITERS

	/**
	 * I want to read lines from a file. And I'm still annoyed.
	 */
	static public BufferedReader createReader(File file) {
		try {
			InputStream is = new FileInputStream(file);
			if (file.getName().toLowerCase().endsWith(".gz")) {
				is = new GZIPInputStream(is);
			}
			return createReader(is);

		} catch (Exception e) {
			if (file == null) {
				throw new RuntimeException("File passed to createReader() was null");
			} else {
				e.printStackTrace();
				throw new RuntimeException("Couldn't create a reader for " +
						file.getAbsolutePath());
			}
		}
		//return null;
	}


	/**
	 * I want to read lines from a stream. If I have to type the
	 * following lines any more I'm gonna send Sun my medical bills.
	 */
	static public BufferedReader createReader(InputStream input) {
		InputStreamReader isr = null;
		try {
			isr = new InputStreamReader(input, "UTF-8");
		} catch (UnsupportedEncodingException e) { }  // not gonna happen
		return new BufferedReader(isr);
	}

	/**
	 * I want to print lines to a file. I have RSI from typing these
	 * eight lines of code so many times.
	 */
	static public PrintWriter createWriter(File file) {
		try {
			createPath(file);  // make sure in-between folders exist
			OutputStream output = new FileOutputStream(file);
			if (file.getName().toLowerCase().endsWith(".gz")) {
				output = new GZIPOutputStream(output);
			}
			return createWriter(output);

		} catch (Exception e) {
			if (file == null) {
				throw new RuntimeException("File passed to createWriter() was null");
			} else {
				e.printStackTrace();
				throw new RuntimeException("Couldn't create a writer for " +
						file.getAbsolutePath());
			}
		}
		//return null;
	}


	/**
	 * I want to print lines to a file. Why am I always explaining myself?
	 * It's the JavaSoft API engineers who need to explain themselves.
	 */
	static public PrintWriter createWriter(OutputStream output) {
		try {
			BufferedOutputStream bos = new BufferedOutputStream(output, 8192);
			OutputStreamWriter osw = new OutputStreamWriter(bos, "UTF-8");
			return new PrintWriter(osw);
		} catch (UnsupportedEncodingException e) { }  // not gonna happen
		return null;
	}

	static public InputStream createInput(File file) {
		if (file == null) {
			throw new IllegalArgumentException("File passed to createInput() was null");
		}
		try {
			InputStream input = new FileInputStream(file);
			if (file.getName().toLowerCase().endsWith(".gz")) {
				return new GZIPInputStream(input);
			}
			return input;

		} catch (IOException e) {
			ProcessingCore.logError("Could not createInput() for " + file, e);
			return null;
		}
	}

	static public byte[] loadBytes(InputStream input) {
		try {
			BufferedInputStream bis = new BufferedInputStream(input);
			ByteArrayOutputStream out = new ByteArrayOutputStream();

			int c = bis.read();
			while (c != -1) {
				out.write(c);
				c = bis.read();
			}
			return out.toByteArray();

		} catch (IOException e) {
			e.printStackTrace();
			//throw new RuntimeException("Couldn't load bytes from stream");
		}
		return null;
	}


	static public byte[] loadBytes(File file) {
		InputStream is = createInput(file);
		return loadBytes(is);
	}


	static public String[] loadStrings(File file) {
		InputStream is = createInput(file);
		if (is != null) return loadStrings(is);
		return null;
	}

	static public String[] loadStrings(InputStream input) {
		try {
			BufferedReader reader =
				new BufferedReader(new InputStreamReader(input, "UTF-8"));

			String lines[] = new String[100];
			int lineCount = 0;
			String line = null;
			while ((line = reader.readLine()) != null) {
				if (lineCount == lines.length) {
					String temp[] = new String[lineCount << 1];
					System.arraycopy(lines, 0, temp, 0, lineCount);
					lines = temp;
				}
				lines[lineCount++] = line;
			}
			reader.close();

			if (lineCount == lines.length) {
				return lines;
			}

			// resize array to appropriate amount for these lines
			String output[] = new String[lineCount];
			System.arraycopy(lines, 0, output, 0, lineCount);
			return output;

		} catch (IOException e) {
			e.printStackTrace();
			//throw new RuntimeException("Error inside loadStrings()");
		}
		return null;
	}



	//////////////////////////////////////////////////////////////

	// FILE OUTPUT

	static public OutputStream createOutput(File file) {
		try {
			createPath(file);  // make sure the path exists
			FileOutputStream fos = new FileOutputStream(file);
			if (file.getName().toLowerCase().endsWith(".gz")) {
				return new GZIPOutputStream(fos);
			}
			return fos;

		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	static public boolean saveStream(File targetFile, InputStream sourceStream) {
		File tempFile = null;
		try {
			File parentDir = targetFile.getParentFile();
			// make sure that this path actually exists before writing
			createPath(targetFile);
			tempFile = File.createTempFile(targetFile.getName(), null, parentDir);

			BufferedInputStream bis = new BufferedInputStream(sourceStream, 16384);
			FileOutputStream fos = new FileOutputStream(tempFile);
			BufferedOutputStream bos = new BufferedOutputStream(fos);

			byte[] buffer = new byte[8192];
			int bytesRead;
			while ((bytesRead = bis.read(buffer)) != -1) {
				bos.write(buffer, 0, bytesRead);
			}

			bos.flush();
			bos.close();
			bos = null;

			if (!tempFile.renameTo(targetFile)) {
				ProcessingCore.logError("Could not rename temporary file " +
						tempFile.getAbsolutePath(), null);
				return false;
			}
			return true;

		} catch (IOException e) {
			if (tempFile != null) {
				tempFile.delete();
			}
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Saves bytes to a specific File location specified by the user.
	 */
	static public void saveBytes(File file, byte buffer[]) {
		File tempFile = null;
		try {
			File parentDir = file.getParentFile();
			tempFile = File.createTempFile(file.getName(), null, parentDir);

			/*
	      String filename = file.getAbsolutePath();
	      createPath(filename);
	      OutputStream output = new FileOutputStream(file);
	      if (file.getName().toLowerCase().endsWith(".gz")) {
	        output = new GZIPOutputStream(output);
	      }
			 */
			OutputStream output = createOutput(tempFile);
			saveBytes(output, buffer);
			output.close();
			output = null;

			if (!tempFile.renameTo(file)) {
				ProcessingCore.logError("Could not rename temporary file " +
						tempFile.getAbsolutePath(), null);
			}

		} catch (IOException e) {
			ProcessingCore.logError("error saving bytes to " + file, e);
			if (tempFile != null) {
				tempFile.delete();
			}
			e.printStackTrace();
		}
	}


	/** Spews a buffer of bytes to an OutputStream. */
	static public void saveBytes(OutputStream output, byte buffer[]) {
		try {
			output.write(buffer);
			output.flush();

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	static public void saveStrings(File file, String strings[]) {
		saveStrings(createOutput(file), strings);
		/*
		    try {
		      String location = file.getAbsolutePath();
		      createPath(location);
		      OutputStream output = new FileOutputStream(location);
		      if (file.getName().toLowerCase().endsWith(".gz")) {
		        output = new GZIPOutputStream(output);
		      }
		      saveStrings(output, strings);
		      output.close();

		    } catch (IOException e) {
		      e.printStackTrace();
		    }
		 */
	}


	static public void saveStrings(OutputStream output, String strings[]) {
		PrintWriter writer = createWriter(output);
		for (int i = 0; i < strings.length; i++) {
			writer.println(strings[i]);
		}
		writer.flush();
		writer.close();
	}

	//////////////////////////////////////////////////////////////

	/**
	 * Takes a path and creates any in-between folders if they don't
	 * already exist. Useful when trying to save to a subfolder that
	 * may not actually exist.
	 */
	static public void createPath(String path) {
		createPath(new File(path));
	}


	static public void createPath(File file) {
		try {
			String parent = file.getParent();
			if (parent != null) {
				File unit = new File(parent);
				if (!unit.exists()) unit.mkdirs();
			}
		} catch (SecurityException se) {
			ProcessingCore.logError("You don't have permissions to create " +
					file.getAbsolutePath(), se);
		}
	}
	
	//////////////////////////////////////////////////////////////
	
	// ARRAYS
	
	  static public String[] concat(String a[], String b[]) {
		    String c[] = new String[a.length + b.length];
		    System.arraycopy(a, 0, c, 0, a.length);
		    System.arraycopy(b, 0, c, a.length, b.length);
		    return c;
		  }

	//////////////////////////////////////////////////////////////

	// STRINGS


	/**
	 * Remove whitespace characters from the beginning and ending
	 * of a String. Works like String.trim() but includes the
	 * unicode nbsp character as well.
	 */
	static public String trim(String str) {
		return str.replace('\u00A0', ' ').trim();
	}


	/**
	 * Trim the whitespace from a String array. This returns a new
	 * array and does not affect the passed-in array.
	 */
	static public String[] trim(String[] array) {
		String[] outgoing = new String[array.length];
		for (int i = 0; i < array.length; i++) {
			if (array[i] != null) {
				outgoing[i] = array[i].replace('\u00A0', ' ').trim();
			}
		}
		return outgoing;
	}


	/**
	 * Join an array of Strings together as a single String,
	 * separated by the whatever's passed in for the separator.
	 */
	static public String join(String str[], char separator) {
		return join(str, String.valueOf(separator));
	}


	/**
	 * Join an array of Strings together as a single String,
	 * separated by the whatever's passed in for the separator.
	 * <P>
	 * To use this on numbers, first pass the array to nf() or nfs()
	 * to get a list of String objects, then use join on that.
	 * <PRE>
	 * e.g. String stuff[] = { "apple", "bear", "cat" };
	 *      String list = join(stuff, ", ");
	 *      // list is now "apple, bear, cat"</PRE>
	 */
	static public String join(String str[], String separator) {
		StringBuffer buffer = new StringBuffer();
		for (int i = 0; i < str.length; i++) {
			if (i != 0) buffer.append(separator);
			buffer.append(str[i]);
		}
		return buffer.toString();
	}


	/**
	 * Split the provided String at wherever whitespace occurs.
	 * Multiple whitespace (extra spaces or tabs or whatever)
	 * between items will count as a single break.
	 * <P>
	 * The whitespace characters are "\t\n\r\f", which are the defaults
	 * for java.util.StringTokenizer, plus the unicode non-breaking space
	 * character, which is found commonly on files created by or used
	 * in conjunction with Mac OS X (character 160, or 0x00A0 in hex).
	 * <PRE>
	 * i.e. splitTokens("a b") -> { "a", "b" }
	 *      splitTokens("a    b") -> { "a", "b" }
	 *      splitTokens("a\tb") -> { "a", "b" }
	 *      splitTokens("a \t  b  ") -> { "a", "b" }</PRE>
	 */
	static public String[] splitTokens(String what) {
		return splitTokens(what, WHITESPACE);
	}


	/**
	 * Splits a string into pieces, using any of the chars in the
	 * String 'delim' as separator characters. For instance,
	 * in addition to white space, you might want to treat commas
	 * as a separator. The delimeter characters won't appear in
	 * the returned String array.
	 * <PRE>
	 * i.e. splitTokens("a, b", " ,") -> { "a", "b" }
	 * </PRE>
	 * To include all the whitespace possibilities, use the variable
	 * WHITESPACE, found in PConstants:
	 * <PRE>
	 * i.e. splitTokens("a   | b", WHITESPACE + "|");  ->  { "a", "b" }</PRE>
	 */
	static public String[] splitTokens(String what, String delim) {
		StringTokenizer toker = new StringTokenizer(what, delim);
		String pieces[] = new String[toker.countTokens()];

		int index = 0;
		while (toker.hasMoreTokens()) {
			pieces[index++] = toker.nextToken();
		}
		return pieces;
	}


	/**
	 * Split a string into pieces along a specific character.
	 * Most commonly used to break up a String along a space or a tab
	 * character.
	 * <P>
	 * This operates differently than the others, where the
	 * single delimeter is the only breaking point, and consecutive
	 * delimeters will produce an empty string (""). This way,
	 * one can split on tab characters, but maintain the column
	 * alignments (of say an excel file) where there are empty columns.
	 */
	static public String[] split(String what, char delim) {
		// do this so that the exception occurs inside the user's
		// program, rather than appearing to be a bug inside split()
		if (what == null) return null;
		//return split(what, String.valueOf(delim));  // huh

		char chars[] = what.toCharArray();
		int splitCount = 0; //1;
		for (int i = 0; i < chars.length; i++) {
			if (chars[i] == delim) splitCount++;
		}
		// make sure that there is something in the input string
		//if (chars.length > 0) {
		// if the last char is a delimeter, get rid of it..
		//if (chars[chars.length-1] == delim) splitCount--;
		// on second thought, i don't agree with this, will disable
		//}
		if (splitCount == 0) {
			String splits[] = new String[1];
			splits[0] = new String(what);
			return splits;
		}
		//int pieceCount = splitCount + 1;
		String splits[] = new String[splitCount + 1];
		int splitIndex = 0;
		int startIndex = 0;
		for (int i = 0; i < chars.length; i++) {
			if (chars[i] == delim) {
				splits[splitIndex++] =
					new String(chars, startIndex, i-startIndex);
				startIndex = i + 1;
			}
		}
		//if (startIndex != chars.length) {
		splits[splitIndex] =
			new String(chars, startIndex, chars.length-startIndex);
		//}
		return splits;
	}


	/**
	 * Split a String on a specific delimiter. Unlike Java's String.split()
	 * method, this does not parse the delimiter as a regexp because it's more
	 * confusing than necessary, and String.split() is always available for
	 * those who want regexp.
	 */
	static public String[] split(String what, String delim) {
		ArrayList<String> items = new ArrayList<String>();
		int index;
		int offset = 0;
		while ((index = what.indexOf(delim, offset)) != -1) {
			items.add(what.substring(offset, index));
			offset = index + delim.length();
		}
		items.add(what.substring(offset));
		String[] outgoing = new String[items.size()];
		items.toArray(outgoing);
		return outgoing;
	}


	static protected HashMap<String, Pattern> matchPatterns;

	static Pattern matchPattern(String regexp) {
		Pattern p = null;
		if (matchPatterns == null) {
			matchPatterns = new HashMap<String, Pattern>();
		} else {
			p = matchPatterns.get(regexp);
		}
		if (p == null) {
			if (matchPatterns.size() == 10) {
				// Just clear out the match patterns here if more than 10 are being
				// used. It's not terribly efficient, but changes that you have >10
				// different match patterns are very slim, unless you're doing
				// something really tricky (like custom match() methods), in which
				// case match() won't be efficient anyway. (And you should just be
				// using your own Java code.) The alternative is using a queue here,
				// but that's a silly amount of work for negligible benefit.
				matchPatterns.clear();
			}
			p = Pattern.compile(regexp, Pattern.MULTILINE | Pattern.DOTALL);
			matchPatterns.put(regexp, p);
		}
		return p;
	}


	/**
	 * Match a string with a regular expression, and returns the match as an
	 * array. The first index is the matching expression, and array elements
	 * [1] and higher represent each of the groups (sequences found in parens).
	 *
	 * This uses multiline matching (Pattern.MULTILINE) and dotall mode
	 * (Pattern.DOTALL) by default, so that ^ and $ match the beginning and
	 * end of any lines found in the source, and the . operator will also
	 * pick up newline characters.
	 */
	static public String[] match(String what, String regexp) {
		Pattern p = matchPattern(regexp);
		Matcher m = p.matcher(what);
		if (m.find()) {
			int count = m.groupCount() + 1;
			String[] groups = new String[count];
			for (int i = 0; i < count; i++) {
				groups[i] = m.group(i);
			}
			return groups;
		}
		return null;
	}


	/**
	 * Identical to match(), except that it returns an array of all matches in
	 * the specified String, rather than just the first.
	 */
	static public String[][] matchAll(String what, String regexp) {
		Pattern p = matchPattern(regexp);
		Matcher m = p.matcher(what);
		ArrayList<String[]> results = new ArrayList<String[]>();
		int count = m.groupCount() + 1;
		while (m.find()) {
			String[] groups = new String[count];
			for (int i = 0; i < count; i++) {
				groups[i] = m.group(i);
			}
			results.add(groups);
		}
		if (results.isEmpty()) {
			return null;
		}
		String[][] matches = new String[results.size()][count];
		for (int i = 0; i < matches.length; i++) {
			matches[i] = (String[]) results.get(i);
		}
		return matches;
	}

	// . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .

	// PROCESSING.APP.BASE
	// Non-UI, Non-PDE specific

	// ...................................................................


	/**
	 * Get the number of lines in a file by counting the number of newline
	 * characters inside a String (and adding 1).
	 */
	static public int countLines(String what) {
		int count = 1;
		for (char c : what.toCharArray()) {
			if (c == '\n') count++;
		}
		return count;
	}


	/**
	 * Same as PApplet.loadBytes(), however never does gzip decoding.
	 */
	static public byte[] loadBytesRaw(File file) throws IOException {
		int size = (int) file.length();
		FileInputStream input = new FileInputStream(file);
		byte buffer[] = new byte[size];
		int offset = 0;
		int bytesRead;
		while ((bytesRead = input.read(buffer, offset, size-offset)) != -1) {
			offset += bytesRead;
			if (bytesRead == 0) break;
		}
		input.close();  // weren't properly being closed
		input = null;
		return buffer;
	}



	/**
	 * Read from a file with a bunch of attribute/value pairs
	 * that are separated by = and ignore comments with #.
	 */
	static public HashMap<String,String> readSettings(File inputFile) {
		HashMap<String,String> outgoing = new HashMap<String,String>();
		if (!inputFile.exists()) return outgoing;  // return empty hash

		String lines[] = loadStrings(inputFile);
		for (int i = 0; i < lines.length; i++) {
			int hash = lines[i].indexOf('#');
			String line = (hash == -1) ?
					lines[i].trim() : lines[i].substring(0, hash).trim();
					if (line.length() == 0) continue;

					int equals = line.indexOf('=');
					if (equals == -1) {
						ProcessingCore.logError("Ignoring illegal line in " 
								+ inputFile + " " + line, null);
						continue;
					}
					String attr = line.substring(0, equals).trim();
					String valu = line.substring(equals + 1).trim();
					outgoing.put(attr, valu);
		}
		return outgoing;
	}


	static public void copyFile(File sourceFile,
			File targetFile) throws IOException {
		InputStream from =
			new BufferedInputStream(new FileInputStream(sourceFile));
		OutputStream to =
			new BufferedOutputStream(new FileOutputStream(targetFile));
		byte[] buffer = new byte[16 * 1024];
		int bytesRead;
		while ((bytesRead = from.read(buffer)) != -1) {
			to.write(buffer, 0, bytesRead);
		}
		to.flush();
		from.close(); // ??
		from = null;
		to.close(); // ??
		to = null;

		targetFile.setLastModified(sourceFile.lastModified());
	}


	/**
	 * Grab the contents of a file as a string.
	 */
	static public String loadFile(File file) throws IOException {
		String[] contents = loadStrings(file);
		if (contents == null) return null;
		return join(contents, "\n");
	}


	/**
	 * Spew the contents of a String object out to a file.
	 */
	static public void saveFile(String str, File file) throws IOException {
		File temp = File.createTempFile(file.getName(), null, file.getParentFile());
		saveStrings(temp, new String[] { str });
		if (file.exists()) {
			boolean result = file.delete();
			if (!result) {
				throw new IOException("Could not remove old version of " +
						file.getAbsolutePath());
			}
		}
		boolean result = temp.renameTo(file);
		if (!result) {
			throw new IOException("Could not replace " +
					file.getAbsolutePath());
		}
	}


	/**
	 * Copy a folder from one place to another. This ignores all dot files and
	 * folders found in the source directory, to avoid copying silly .DS_Store
	 * files and potentially troublesome .svn folders.
	 */
	static public void copyDir(File sourceDir,
			File targetDir) throws IOException {
		if (sourceDir.equals(targetDir)) {
			final String urDum = "source and target directories are identical";
			throw new IllegalArgumentException(urDum);
		}
		targetDir.mkdirs();
		String files[] = sourceDir.list();
		for (int i = 0; i < files.length; i++) {
			// Ignore dot files (.DS_Store), dot folders (.svn) while copying
			if (files[i].charAt(0) == '.') continue;
			//if (files[i].equals(".") || files[i].equals("..")) continue;
			File source = new File(sourceDir, files[i]);
			File target = new File(targetDir, files[i]);
			if (source.isDirectory()) {
				//target.mkdirs();
				copyDir(source, target);
				target.setLastModified(source.lastModified());
			} else {
				copyFile(source, target);
			}
		}
	}


	/**
	 * Remove all files in a directory and the directory itself.
	 */
	static public void removeDir(File dir) {
		if (dir.exists()) {
			removeDescendants(dir);
			if (!dir.delete()) {
				ProcessingCore.logError("Could not delete " + dir, null);
			}
		}
	}


	/**
	 * Recursively remove all files within a directory,
	 * used with removeDir(), or when the contents of a dir
	 * should be removed, but not the directory itself.
	 * (i.e. when cleaning temp files from lib/build)
	 */
	static public void removeDescendants(File dir) {
		if (!dir.exists()) return;

		String files[] = dir.list();
		for (int i = 0; i < files.length; i++) {
			if (files[i].equals(".") || files[i].equals("..")) continue;
			File dead = new File(dir, files[i]);
			if (!dead.isDirectory()){
				if (!dead.delete()) 
					ProcessingCore.logError("Could not delete " + dead, null);
			} else {
				removeDir(dead);
			}
		}
	}

	/**
	 * Calculate the size of the contents of a folder.
	 * Used to determine whether sketches are empty or not.
	 * Note that the function calls itself recursively.
	 */
	static public int calcFolderSize(File folder) {
		int size = 0;

		String files[] = folder.list();
		// null if folder doesn't exist, happens when deleting sketch
		if (files == null) return -1;

		for (int i = 0; i < files.length; i++) {
			if (files[i].equals(".") || (files[i].equals("..")) ||
					files[i].equals(".DS_Store")) continue;
			File fella = new File(folder, files[i]);
			if (fella.isDirectory()) {
				size += calcFolderSize(fella);
			} else {
				size += (int) fella.length();
			}
		}
		return size;
	}


	/**
	 * Recursively creates a list of all files within the specified folder,
	 * and returns a list of their relative paths.
	 * Ignores any files/folders prefixed with a dot.
	 */
	static public String[] listFiles(String path, boolean relative) {
		return listFiles(new File(path), relative);
	}


	static public String[] listFiles(File folder, boolean relative) {
		String path = folder.getAbsolutePath();
		Vector<String> vector = new Vector<String>();
		listFiles(relative ? (path + File.separator) : "", path, vector);
		String outgoing[] = new String[vector.size()];
		vector.copyInto(outgoing);
		return outgoing;
	}


	static protected void listFiles(String basePath,
			String path, Vector<String> vector) {
		File folder = new File(path);
		String list[] = folder.list();
		if (list == null) return;

		for (int i = 0; i < list.length; i++) {
			if (list[i].charAt(0) == '.') continue;

			File file = new File(path, list[i]);
			String newPath = file.getAbsolutePath();
			if (newPath.startsWith(basePath)) {
				newPath = newPath.substring(basePath.length());
			}
			vector.add(newPath);
			if (file.isDirectory()) {
				listFiles(basePath, newPath, vector);
			}
		}
	}

}