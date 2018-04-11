/*
 * Copyright © 2017 Cask Data, Inc.
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

package co.cask.http;

/**
 * Util class to Detect OS type and other OS related parameters.
 */
public final class OsUtils {

    private OsUtils(){}

    private static final String OS_NAME_KEY = "os.name";

    public static boolean isOSLinux() {
        String osName = System.getProperty(OS_NAME_KEY);
        return osName.indexOf("linux") > 0;
    }
}
