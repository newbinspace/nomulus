// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.flows.session;

import static com.google.common.collect.Sets.difference;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.collect.ImmutableSet;

import google.registry.flows.EppException;
import google.registry.flows.EppException.AuthenticationErrorClosingConnectionException;
import google.registry.flows.EppException.AuthenticationErrorException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppException.UnimplementedObjectServiceException;
import google.registry.flows.EppException.UnimplementedOptionException;
import google.registry.flows.Flow;
import google.registry.flows.TransportCredentials;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import google.registry.model.eppinput.EppInput.Login;
import google.registry.model.eppinput.EppInput.Options;
import google.registry.model.eppinput.EppInput.Services;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.Result.Code;
import google.registry.model.registrar.Registrar;
import google.registry.util.FormattingLogger;

import java.util.Objects;
import java.util.Set;

/**
 * An EPP flow for login.
 *
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.EppException.UnimplementedObjectServiceException}
 * @error {@link google.registry.flows.EppException.UnimplementedProtocolVersionException}
 * @error {@link google.registry.flows.GaeUserCredentials.BadGaeUserIdException}
 * @error {@link google.registry.flows.GaeUserCredentials.UserNotLoggedInException}
 * @error {@link google.registry.flows.TlsCredentials.BadRegistrarCertificateException}
 * @error {@link google.registry.flows.TlsCredentials.BadRegistrarIpAddressException}
 * @error {@link google.registry.flows.TlsCredentials.MissingRegistrarCertificateException}
 * @error {@link google.registry.flows.TlsCredentials.NoSniException}
 * @error {@link LoginFlow.AlreadyLoggedInException}
 * @error {@link LoginFlow.BadRegistrarClientIdException}
 * @error {@link LoginFlow.BadRegistrarPasswordException}
 * @error {@link LoginFlow.TooManyFailedLoginsException}
 * @error {@link LoginFlow.PasswordChangesNotSupportedException}
 * @error {@link LoginFlow.RegistrarAccountNotActiveException}
 * @error {@link LoginFlow.UnsupportedLanguageException}
 */
public class LoginFlow extends Flow {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  /** This is the IANA ID used for the internal account of the registry. */
  private static final long INTERNAL_IANA_REGISTRAR_ID = 9999L;

  /** Maximum number of failed login attempts allowed per connection. */
  private static final int MAX_FAILED_LOGIN_ATTEMPTS_PER_CONNECTION = 3;

  /** Run the flow and log errors. */
  @Override
  public final EppOutput run() throws EppException {
    try {
      return runWithoutLogging();
    } catch (EppException e) {
      logger.warning("Login failed: " + e.getMessage());
      throw e;
    }
  }

  /** Run the flow without bothering to log errors. The {@link #run} method will do that for us. */
  public final EppOutput runWithoutLogging() throws EppException {
    Login login = (Login) eppInput.getCommandWrapper().getCommand();
    if (getClientId() != null) {
      throw new AlreadyLoggedInException();
    }
    Options options = login.getOptions();
    if (!ProtocolDefinition.LANGUAGE.equals(options.getLanguage())) {
      throw new UnsupportedLanguageException();
    }
    Services services = login.getServices();
    Set<String> unsupportedObjectServices = difference(
        nullToEmpty(services.getObjectServices()),
        ProtocolDefinition.SUPPORTED_OBJECT_SERVICES);
    if (!unsupportedObjectServices.isEmpty()) {
      throw new UnimplementedObjectServiceException();
    }
    ImmutableSet.Builder<String> serviceExtensionUrisBuilder = new ImmutableSet.Builder<>();
    for (String uri : nullToEmpty(services.getServiceExtensions())) {
      ServiceExtension serviceExtension = ProtocolDefinition.getServiceExtensionFromUri(uri);
      if (serviceExtension == null) {
        throw new UnimplementedExtensionException();
      }
      serviceExtensionUrisBuilder.add(uri);
    }
    Registrar registrar = Registrar.loadByClientId(login.getClientId());
    if (registrar == null) {
      throw new BadRegistrarClientIdException(login.getClientId());
    }

    TransportCredentials credentials = sessionMetadata.getTransportCredentials();
    // AuthenticationErrorExceptions will propagate up through here.
    if (credentials != null) {  // Allow no-credential logins, for load-testing and RDE.
      try {
        credentials.validate(registrar);
      } catch (AuthenticationErrorException e) {
        sessionMetadata.incrementFailedLoginAttempts();
        throw e;
      }
    }

    final boolean requiresLoginCheck = credentials == null || !credentials.performsLoginCheck();
    if (requiresLoginCheck && !registrar.testPassword(login.getPassword())) {
      sessionMetadata.incrementFailedLoginAttempts();
      if (sessionMetadata.getFailedLoginAttempts() > MAX_FAILED_LOGIN_ATTEMPTS_PER_CONNECTION) {
        throw new TooManyFailedLoginsException();
      } else {
        throw new BadRegistrarPasswordException();
      }
    }
    if (registrar.getState().equals(Registrar.State.PENDING)) {
      throw new RegistrarAccountNotActiveException();
    }
    if (login.getNewPassword() != null) {  // We don't support in-band password changes.
      throw new PasswordChangesNotSupportedException();
    }

    // We are in!
    sessionMetadata.resetFailedLoginAttempts();
    sessionMetadata.setClientId(login.getClientId());
    sessionMetadata.setSuperuser(
        Objects.equals(INTERNAL_IANA_REGISTRAR_ID, registrar.getIanaIdentifier()));
    sessionMetadata.setServiceExtensionUris(serviceExtensionUrisBuilder.build());
    return createOutput(Code.Success);
  }

  /** Registrar with this client ID could not be found. */
  static class BadRegistrarClientIdException extends AuthenticationErrorException {
    public BadRegistrarClientIdException(String clientId) {
      super("Registrar with this client ID could not be found: " + clientId);
    }
  }

  /** Registrar password is incorrect. */
  static class BadRegistrarPasswordException extends AuthenticationErrorException {
    public BadRegistrarPasswordException() {
      super("Registrar password is incorrect");
    }
  }

  /** Registrar login failed too many times. */
  static class TooManyFailedLoginsException extends AuthenticationErrorClosingConnectionException {
    public TooManyFailedLoginsException() {
      super("Registrar login failed too many times");
    }
  }

  /** Registrar account is not active. */
  static class RegistrarAccountNotActiveException extends AuthorizationErrorException {
    public RegistrarAccountNotActiveException() {
      super("Registrar account is not active");
    }
  }

  /** Registrar is already logged in. */
  static class AlreadyLoggedInException extends CommandUseErrorException {
    public AlreadyLoggedInException() {
      super("Registrar is already logged in");
    }
  }

  /** Specified language is not supported. */
  static class UnsupportedLanguageException extends ParameterValuePolicyErrorException {
    public UnsupportedLanguageException() {
      super("Specified language is not supported");
    }
  }

  /** In-band password changes are not supported. */
  static class PasswordChangesNotSupportedException extends UnimplementedOptionException {
    public PasswordChangesNotSupportedException() {
      super("In-band password changes are not supported");
    }
  }
}
