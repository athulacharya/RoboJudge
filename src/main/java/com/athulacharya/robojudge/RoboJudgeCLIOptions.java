/*
 * Copyright 2020 Athul K. Acharya
 * Derived from Google's InfiniteStreamRecognizeOptions.java at [1]; relevant portions (c) 2019 Google LLC.
 *
 * [1] https://github.com/GoogleCloudPlatform/java-docs-samples/tree/master/speech/cloud-client/src/main/java/com/example/speech
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.athulacharya.robojudge;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class RoboJudgeCLIOptions {
    String langCode = "en-US"; // by default english US
    String wavDirectory = "";
    String hintsFile = null;

    /** Construct an RoboJudgeCLIOptions class from command line flags. */
    public static RoboJudgeCLIOptions fromFlags(String[] args) {
        Options options = new Options();
        options.addOption(
                Option.builder()
                        .type(String.class)
                        .longOpt("lang_code")
                        .hasArg()
                        .desc("Language code")
                        .build());
        options.addOption(
                Option.builder()
                    .type(String.class)
                    .longOpt("d")
                    .hasArg()
                    .numberOfArgs(1)
                    .required()
                    .desc("Directory of .wav files of questions")
                    .build());
        options.addOption(
                Option.builder()
                    .type(String.class)
                    .longOpt("h")
                    .hasArg()
                    .numberOfArgs(1)
                    .desc("Hints file")
                    .build());

        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine;
        try {
            commandLine = parser.parse(options, args);
            RoboJudgeCLIOptions res = new RoboJudgeCLIOptions();

            if (commandLine.hasOption("lang_code")) {
                res.langCode = commandLine.getOptionValue("lang_code");
            }

            if (commandLine.hasOption('d')) {
                res.wavDirectory = commandLine.getOptionValue('d');
            }

            if (commandLine.hasOption('h')) {
                res.hintsFile = commandLine.getOptionValue('h');
            }
            return res;
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            return null;
        }
    }
}