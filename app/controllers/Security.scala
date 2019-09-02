package controllers

import com.google.inject.name.Named
import com.google.inject.{AbstractModule, Provides}
import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.api.crypto.{Crypter, CrypterAuthenticatorEncoder, Signer}
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.api.services.AuthenticatorService
import com.mohiva.play.silhouette.api.util._
import com.mohiva.play.silhouette.api.{Authorization, Environment, EventBus, Silhouette, SilhouetteProvider}
import com.mohiva.play.silhouette.api.actions.SecuredErrorHandler
import com.mohiva.play.silhouette.crypto.{JcaSigner, JcaSignerSettings, JcaCrypter, JcaCrypterSettings}
import com.mohiva.play.silhouette.impl.authenticators.{CookieAuthenticator, CookieAuthenticatorSettings, CookieAuthenticatorService}
import com.mohiva.play.silhouette.impl.providers.{CredentialsProvider, SocialProviderRegistry, SocialStateHandler, DefaultSocialStateHandler, OAuth2Settings}
import com.mohiva.play.silhouette.impl.providers.oauth2.GoogleProvider
import com.mohiva.play.silhouette.impl.providers.state.{CsrfStateItemHandler, CsrfStateSettings}
import com.mohiva.play.silhouette.impl.util.{DefaultFingerprintGenerator, SecureRandomIDGenerator}
import com.mohiva.play.silhouette.password.BCryptPasswordHasher
import com.mohiva.play.silhouette.persistence.repositories.DelegableAuthInfoRepository
import com.typesafe.config.Config
import javax.inject.Inject
import services.user.{User, UserService}
import services.user.Roles.Role
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import net.codingwell.scalaguice.ScalaModule
import play.api.Configuration
import play.api.mvc.{Cookie, CookieHeaderEncoding, Request, RequestHeader, Results}
import play.api.libs.ws.WSClient
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.reflect.ClassTag


class SilhouetteSecurity extends AbstractModule with ScalaModule {

  private def getAppSecret(config: Configuration) =
    config.get[String]("play.http.secret.key")

  implicit val sameSiteReader: ValueReader[Option[Option[Cookie.SameSite]]] = new ValueReader[Option[Option[Cookie.SameSite]]] {

    def read(config: Config, path: String): Option[Option[Cookie.SameSite]] = {
      if (config.hasPathOrNull(path)) {
        if (config.getIsNull(path))
          Some(None)
        else {
          Some(Cookie.SameSite.parse(config.getString(path)))
        }
      } else {
        None
      }
    }

  }

  override def configure(): Unit = {
    bind[Silhouette[Security.Env]].to[SilhouetteProvider[Security.Env]]
    bind[SecuredErrorHandler].to[RecogitoSecuredErrorHandler]
    bind[PasswordHasher].toInstance(new BCryptPasswordHasher)
    bind[IDGenerator].toInstance(new SecureRandomIDGenerator())
    bind[FingerprintGenerator].toInstance(new DefaultFingerprintGenerator(false))
    bind[EventBus].toInstance(EventBus())
    bind[Clock].toInstance(Clock())
  }

  @Provides
  def provideEnvironment(
    userService: UserService,
    authenticatorService: AuthenticatorService[CookieAuthenticator],
    eventBus: EventBus
  ): Environment[Security.Env] = Environment[Security.Env](
    userService,
    authenticatorService,
    Seq(),
    eventBus
  )

  @Provides
  def provideAuthenticatorService(
    @Named("authenticator-signer") signer: Signer,
    @Named("authenticator-crypter") crypter: Crypter,
    cookieHeaderEncoding: CookieHeaderEncoding,
    fingerprintGenerator: FingerprintGenerator,
    idGenerator: IDGenerator,
    configuration: Configuration,
    clock: Clock
  ): AuthenticatorService[CookieAuthenticator] = {
    val config = CookieAuthenticatorSettings(
      cookieName = "id",
      cookiePath = "/",
      cookieDomain = None,
      secureCookie = false, // Send cookie in HTTP and HTTPS modes
      httpOnlyCookie = true, // Not accessible through JS
      useFingerprinting = true,
      cookieMaxAge = None,
      authenticatorIdleTimeout = Some(1.hour),
      authenticatorExpiry = 24.hours)

    val enc = new CrypterAuthenticatorEncoder(crypter)
    new CookieAuthenticatorService(config, None, signer, cookieHeaderEncoding, enc  , fingerprintGenerator, idGenerator, clock)
  }

  @Provides
  def provideAuthInfoRepository(): AuthInfoRepository =
    new AuthInfoRepositoryImpl()

  @Provides
  def providePasswordHasherRegistry(passwordHasher: PasswordHasher): PasswordHasherRegistry =
    PasswordHasherRegistry(passwordHasher)

  @Provides
  def provideCredentialsProvider(
    authInfoRepository: AuthInfoRepository,
    passwordHasherRegistry: PasswordHasherRegistry
  ): CredentialsProvider =
    new CredentialsProvider(authInfoRepository, passwordHasherRegistry)

  @Provides
  @Named("authenticator-signer")
  def provideAuthenticatorSigner(configuration: Configuration): Signer = {
    val settings = JcaSignerSettings(getAppSecret(configuration))
    new JcaSigner(settings)
  }

  @Provides
  @Named("authenticator-crypter")
  def provideAuthenticatorCrypter(configuration: Configuration): Crypter = {
    val settings = JcaCrypterSettings(getAppSecret(configuration))
    new JcaCrypter(settings)
  }

  @Provides
  def provideSocialProviderRegistry(googleProvider: GoogleProvider) = {
    SocialProviderRegistry(Seq(googleProvider))
  }

  @Provides
  def provideHTTPLayer(client: WSClient): HTTPLayer = new PlayHTTPLayer(client)

  @Provides
  def provideGoogleProvider(
    httpLayer: HTTPLayer,
    socialStateHandler: SocialStateHandler,
    configuration: Configuration
  ) = new GoogleProvider(httpLayer, socialStateHandler, configuration.underlying.as[OAuth2Settings]("silhouette.google"))

  @Provides @Named("social-state-signer")
  def provideSocialStateSigner(configuration: Configuration): Signer = {
    val config = configuration.underlying.as[JcaSignerSettings]("silhouette.socialStateHandler.signer")
    new JcaSigner(config)
  }

  @Provides
  def provideSocialStateHandler(
    @Named("social-state-signer") signer: Signer,
    csrfStateItemHandler: CsrfStateItemHandler
  ): SocialStateHandler = new DefaultSocialStateHandler(Set(csrfStateItemHandler), signer)

  @Provides
  def provideCsrfStateItemHandler(
    idGenerator: IDGenerator,
    @Named("csrf-state-item-signer") signer: Signer,
    configuration: Configuration): CsrfStateItemHandler = {
    val settings = configuration.underlying.as[CsrfStateSettings]("silhouette.csrfStateItemHandler")
    new CsrfStateItemHandler(settings, idGenerator, signer)
  }
  
  @Provides @Named("csrf-state-item-signer")
  def provideCSRFStateItemSigner(configuration: Configuration): Signer = {
    val config = configuration.underlying.as[JcaSignerSettings]("silhouette.csrfStateItemHandler.signer")
    new JcaSigner(config)
  }

}

object Security {

  val PROVIDER_ID = "recogito.pelagios.org"

  trait Env extends com.mohiva.play.silhouette.api.Env {

    type I = User

    type A = CookieAuthenticator

  }

  case class WithRole(role: Role) extends Authorization[User, CookieAuthenticator] {

    def isAuthorized[B](user: User, authenticator: CookieAuthenticator)(implicit request: Request[B]) = {
      Future.successful(user.hasRole(role))
    }

  }

}

/** https://github.com/Ciantic/play-silhouette-seed-minimal/blob/master/app/models/daos/AuthInfoDAO.scala **/
class AuthInfoRepositoryImpl[C <: AuthInfo](implicit tag: ClassTag[C]) extends AuthInfoRepository {

  def find[T <: AuthInfo](loginInfo: LoginInfo)(implicit tag: scala.reflect.ClassTag[T]): Future[Option[T]] = {
    Future.successful(None)
  }
  
  def add[T <: AuthInfo](loginInfo: LoginInfo, authInfo: T): Future[T] = {
    Future.successful(authInfo)
  }

  def update[T <: AuthInfo](loginInfo: LoginInfo, authInfo: T): Future[T] = {
    Future.successful(authInfo)
  }

  def save[T <: AuthInfo](loginInfo: LoginInfo, authInfo: T): Future[T] = {
    find(loginInfo).flatMap {
      case Some(_) => update(loginInfo, authInfo)
      case None => add(loginInfo, authInfo)
    }
  }

  def remove[T <: AuthInfo](loginInfo: LoginInfo)(implicit tag: scala.reflect.ClassTag[T]): Future[Unit] = {
    Future.successful(())
  }

}

/** https://github.com/mohiva/play-silhouette-seed/blob/master/app/utils/auth/CustomSecuredErrorHandler.scala **/
class RecogitoSecuredErrorHandler @Inject()() extends SecuredErrorHandler {

  override def onNotAuthenticated(implicit request: RequestHeader) = {
    Future.successful(
      Results.Redirect(controllers.landing.routes.LoginLogoutController.showLoginForm())
        .withSession("access_uri" -> request.uri)
    )
  }

  override def onNotAuthorized(implicit request: RequestHeader) = {
    Future.successful(
      Results.Redirect(controllers.landing.routes.LoginLogoutController.showLoginForm())
        .withSession("access_uri" -> request.uri)
    )
  }
  
}
