// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import org.junit.Test;

/** Unit tests for {@link DeleteDomainCommand}. */
public class DeleteDomainCommandTest extends EppToolCommandTestCase<DeleteDomainCommand> {

  @Test
  public void testSuccess() throws Exception {
    runCommand("--client=NewRegistrar", "--domain_name=example.tld", "--force", "--reason=Test");
    eppVerifier.verifySent("domain_delete.xml");
  }

  @Test
  public void testSuccess_multipleWordReason() throws Exception {
    runCommandForced(
        "--client=NewRegistrar", "--domain_name=example.tld", "--reason=\"Test test\"");
    eppVerifier.verifySent("domain_delete_multiple_word_reason.xml");
  }

  @Test
  public void testSuccess_immediately() throws Exception {
    runCommandForced(
        "--client=NewRegistrar", "--domain_name=example.tld", "--immediately", "--reason=Test");
    eppVerifier.expectSuperuser().verifySent("domain_delete_immediately.xml");
  }

  @Test
  public void testSuccess_requestedByRegistrarFalse() throws Exception {
    runCommandForced("--client=NewRegistrar", "--domain_name=example.tld", "--reason=Test");
    eppVerifier.verifySent("domain_delete.xml");
  }

  @Test
  public void testSuccess_requestedByRegistrarTrue() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--domain_name=example.tld",
        "--reason=Test",
        "--registrar_request");
    eppVerifier.verifySent("domain_delete_by_registrar.xml");
  }

  @Test
  public void testFailure_noReason() {
    assertThrows(
        ParameterException.class,
        () -> runCommand("--client=NewRegistrar", "--domain_name=example.tld", "--force"));
  }

  @Test
  public void testFailure_missingClientId() {
    assertThrows(
        ParameterException.class,
        () -> runCommand("--domain_name=example.tld", "--force", "--reason=Test"));
  }

  @Test
  public void testFailure_missingDomainName() {
    assertThrows(
        ParameterException.class,
        () -> runCommand("--client=NewRegistrar", "--force", "--reason=Test"));
  }

  @Test
  public void testFailure_unknownFlag() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommand(
                "--client=NewRegistrar",
                "--domain_name=example.tld",
                "--force",
                "--reason=Test",
                "--foo"));
  }

  @Test
  public void testFailure_mainParameter() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommand(
                "--client=NewRegistrar",
                "--domain_name=example.tld",
                "--force",
                "--reason=Test",
                "foo"));
  }
}
