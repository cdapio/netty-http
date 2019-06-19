/*
 * Copyright © 2018 Waarp SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.http;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A set a file manipulation methods used for testing purposes.
 */
public class FileUtils {
    
    /** Buffer size used for reading and writing. */
    private static final int BUFFER_SIZE = 8192;
    
    /**
     * Reads all bytes from an input stream and writes them to an output stream.
     * @param in            The input stream of the source file.
     * @param out           The output stream of the destination file.
     * @throws IOException  Thrown if the program could not read the input stream or write in the output stream.
     */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }
    
    /**
     * Copies all bytes from a file to an output stream.
     * @param source        The path of the source file.
     * @param out           The output stream of the destination file.
     * @throws IOException  Thrown if the program could not read the input stream or write in the output stream.
     */
    public static void copy(String source, OutputStream out) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(source);
            copy(in, out);
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }
    
    /**
     * Copies all bytes from an input stream to a file.
     * @param in            The input stream of the source file.
     * @param output        The path of the destination file.
     * @throws IOException  Thrown if the program could not read the input stream or write in the output stream.
     */
    public static void copy(InputStream in, String output) throws IOException {
        OutputStream out = null;
        try {
            out = new FileOutputStream(output);
            copy(in, out);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }
}
