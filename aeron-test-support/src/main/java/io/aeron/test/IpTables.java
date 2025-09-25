/*
 * Copyright 2014-2024 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.test;

import org.agrona.Strings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class IpTables
{
    public static boolean runIpTablesCmd(final boolean ignoreError, final List<String> command)
    {
        try
        {
            final Process start = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

            final ByteArrayOutputStream commandOutput = new ByteArrayOutputStream();
            try (InputStream inputStream = start.getInputStream())
            {
                final byte[] block = new byte[4096];
                while (start.isAlive())
                {
                    final int read = inputStream.read(block);
                    if (0 < read)
                    {
                        commandOutput.write(block, 0, read);
                    }
                    Tests.yield();
                }
            }

            final boolean isSuccess = 0 == start.exitValue();
            if (!isSuccess && !ignoreError)
            {
                final String commandMsg = commandOutput.toString(StandardCharsets.UTF_8);
                throw new RuntimeException("Command: '" + String.join(" ", command) + "' failed - " + commandMsg);
            }

            return isSuccess;
        }
        catch (final IOException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    public static void deleteChain(final String chainName)
    {
        runIpTablesCmd(true, List.of("sudo", "iptables", "-X", chainName));
    }

    public static void removeFromInput(final String chainName)
    {
        final List<String> command = List.of("sudo", "-n", "iptables", "--delete", "INPUT", "--jump", chainName);
        boolean isSuccess;
        do
        {
            isSuccess = runIpTablesCmd(true, command);
        }
        while (isSuccess);
    }

    public static void addToInput(final String chainName)
    {
        runIpTablesCmd(true, List.of("sudo", "-n", "iptables", "--insert", "INPUT", "--jump", chainName));
    }

    public static void makeSymmetricNetworkPartition(
        final String chainName, final List<String> groupA, final List<String> groupB)
    {
        for (final String hostA : groupA)
        {
            for (final String hostB : groupB)
            {
                dropUdpTrafficBetweenHosts(chainName, hostB, "", hostA, "");
            }
            for (final String hostB : groupB)
            {
                dropUdpTrafficBetweenHosts(chainName, hostA, "", hostB, "");
            }
        }
    }

    public static void dropUdpTrafficBetweenHosts(
        final String chainName,
        final String srcHostname,
        final String srcPort,
        final String destHostname,
        final String destPort)
    {
        final List<String> command = new ArrayList<>();
        command.add("sudo");
        command.add("-n");
        command.add("iptables");
        command.add("--insert");
        command.add(chainName);
        command.add("--ipv4");
        command.add("--protocol");
        command.add("udp");

        command.add("--source");
        command.add(srcHostname);
        if (!Strings.isEmpty(srcPort))
        {
            command.add("--source-port");
            command.add(srcPort);
        }

        command.add("--destination");
        command.add(destHostname);
        if (!Strings.isEmpty(destPort))
        {
            command.add("--destination-port");
            command.add(destPort);
        }

        command.add("--jump");
        command.add("DROP");

        runIpTablesCmd(false, command);
    }

    public static void createChain(final String chainName)
    {
        runIpTablesCmd(true, List.of("sudo", "-n", "iptables", "--new-chain", chainName));
        runIpTablesCmd(false, List.of("sudo", "-n", "iptables", "--append", chainName, "--jump", "RETURN"));
    }

    public static void flushChain(final String chainName)
    {
        runIpTablesCmd(true, List.of("sudo", "-n", "iptables", "--flush", chainName));
    }

    public static void setupChain(final String chainName)
    {
        createChain(chainName);
        flushChain(chainName);
        addToInput(chainName);
    }

    public static void tearDownChain(final String chainName)
    {
        flushChain(chainName);
        removeFromInput(chainName);
        deleteChain(chainName);
    }
}
