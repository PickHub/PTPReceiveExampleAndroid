package edu.kit.tm.android.torp2psend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipInputStream;

import android.content.Context;


public class Utility {


	public static void copy(Context context, String location, int resource, boolean zip) throws IOException {
		File destination = new File(location);
		if (destination.exists()) return;

		InputStream in = context.getResources().openRawResource(resource);
		FileOutputStream out = new FileOutputStream(destination);
		ZipInputStream zis = null;

		if (zip) {
			zis = new ZipInputStream(in);
			zis.getNextEntry();
			in = zis;
		}

		byte[] buffer = new byte[4096];
		int bytecount;
		while ((bytecount = in.read(buffer)) > 0)
			out.write(buffer, 0, bytecount);

		out.close();
		in.close();
		if (zip) zis.close();
	}

}
