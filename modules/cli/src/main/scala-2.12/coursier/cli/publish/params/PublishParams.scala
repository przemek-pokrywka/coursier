package coursier.cli.publish.params

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.TimeUnit

import caseapp.Tag
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits._
import com.squareup.okhttp.OkHttpClient
import coursier.cache.loggers.RefreshLogger
import coursier.cli.publish.PublishRepository
import coursier.cli.publish.conf.Conf
import coursier.cli.publish.options.PublishOptions
import coursier.params.CacheParams
import coursier.publish.Content
import coursier.publish.checksum.logger.{BatchChecksumLogger, ChecksumLogger, InteractiveChecksumLogger}
import coursier.publish.download.logger.{DownloadLogger, SimpleDownloadLogger}
import coursier.publish.signing.{GpgSigner, NopSigner, Signer}
import coursier.publish.signing.logger.{BatchSignerLogger, InteractiveSignerLogger, SignerLogger}
import coursier.publish.sonatype.SonatypeApi
import coursier.publish.upload.logger.{BatchUploadLogger, InteractiveUploadLogger, UploadLogger}
import coursier.util.Task

final case class PublishParams(
  repository: RepositoryParams,
  metadata: MetadataParams,
  singlePackage: SinglePackageParams,
  directory: DirectoryParams,
  checksum: ChecksumParams,
  signature: SignatureParams,
  cache: CacheParams,
  verbosity: Int,
  dummy: Boolean,
  batch: Boolean,
  sbtOutputFrame: Option[Int]
) {
  def withConf(conf: Conf): PublishParams = {

    var p = this

    for (o <- conf.organization.organization if p.metadata.organization.isEmpty)
      p = p.copy(
        metadata = p.metadata.copy(organization = Some(o))
      )

    // TODO Take conf.organization.url into account

    for (v <- conf.version if p.metadata.version.isEmpty)
      p = p.copy(
        metadata = p.metadata.copy(version = Some(v))
      )

    for (url <- conf.homePage if p.metadata.homePage.isEmpty)
      p = p.copy(
        metadata = p.metadata.copy(homePage = Some(url))
      )

    for (licenses <- conf.licenses if p.metadata.licenses.isEmpty)
      p = p.copy(
        metadata = p.metadata.copy(licenses = Some(licenses))
      )

    for (developers <- conf.developers if p.metadata.developersOpt.isEmpty)
      p = p.copy(
        metadata = p.metadata.copy(developersOpt = Some(developers))
      )

    p
  }

  def dirName(dir: Path, short: Option[String] = None): String =
    if (verbosity >= 2)
      dir.normalize().toAbsolutePath.toString
    else
      short.getOrElse(dir.getFileName.toString)


  lazy val signer: Signer =
    if (signature.gpg) {
      val key = signature.gpgKeyOpt match {
        case None => GpgSigner.Key.Default
        case Some(id) => GpgSigner.Key.Id(id)
      }
      GpgSigner(key)
    } else
      NopSigner

  def maybeWarnSigner(out: PrintStream): Unit =
    (repository.repository, signer) match {
      case (_: PublishRepository.Sonatype, NopSigner) =>
        out.println("Warning: --sonatype passed, but signing not enabled, trying to proceed anyway")
      case _ =>
    }


  // Signing dummy stuff to trigger any gpg dialog, before our signer logger is set up.
  // The gpg dialog and our logger seem to conflict else, leaving the terminal in a bad state.
  def initSigner: Task[Unit] =
    signer
      .sign(Content.InMemory(Instant.EPOCH, "hello".getBytes(StandardCharsets.UTF_8)))
      .flatMap {
        case Left(msg) => Task.fail(new Exception(
          s"Failed to sign: $msg"
        ))
        case Right(_) => Task.point(())
      }

  def sonatypeApiOpt(out: PrintStream): Option[(PublishRepository.Sonatype, SonatypeApi)] =
    repository.repository match {
      case s: PublishRepository.Sonatype =>
        // this can't be shutdown anyway
        val client = new OkHttpClient
        // Sonatype can be quite slow
        client.setReadTimeout(60L, TimeUnit.SECONDS)
        val authentication = repository.repository.snapshotRepo.authentication
        if (authentication.isEmpty && verbosity >= 0)
          out.println("Warning: no Sonatype credentials passed, trying to proceed anyway")
        Some((s, SonatypeApi(client, s.restBase, repository.repository.snapshotRepo.authentication, verbosity)))
      case _ =>
        None
    }

  def downloadLogger(out: PrintStream): DownloadLogger =
    new SimpleDownloadLogger(out, verbosity)

  def signerLogger(out: PrintStream): SignerLogger =
    if (batch)
      new BatchSignerLogger(out, verbosity)
    else
      InteractiveSignerLogger.create(out, verbosity)

  def checksumLogger(out: PrintStream): ChecksumLogger =
    if (batch)
      new BatchChecksumLogger(out, verbosity)
    else
      InteractiveChecksumLogger.create(out, verbosity)

  def uploadLogger(out: PrintStream, isLocal: Boolean): UploadLogger =
    if (batch)
      new BatchUploadLogger(out, dummy, isLocal)
    else
      InteractiveUploadLogger.create(out, dummy, isLocal)

}

object PublishParams {
  def apply(options: PublishOptions, args: Seq[String]): ValidatedNel[String, PublishParams] = {

    // FIXME Get from options
    val defaultScalaVersion = scala.util.Properties.versionNumberString

    val repositoryV = RepositoryParams(options.repositoryOptions)
    val metadataV = MetadataParams(options.metadataOptions, defaultScalaVersion)
    val singlePackageV = SinglePackageParams(options.singlePackageOptions)
    val directoryV = DirectoryParams(options.directoryOptions, args)
    val checksumV = ChecksumParams(options.checksumOptions)
    val signatureV = SignatureParams(options.signatureOptions)
    val cacheV = options.cacheOptions.params

    val verbosityV =
      (options.quiet, Tag.unwrap(options.verbose)) match {
        case (Some(true), 0) =>
          Validated.validNel(-1)
        case (Some(true), n) =>
          assert(n > 0)
          Validated.invalidNel("Cannot specify both --quiet and --verbose")
        case (_, n) =>
          Validated.validNel(n)
      }

    val sbtOutputFrame =
      Some(options.sbtOutputFrame).filter(_ > 0)

    val dummy = options.dummy
    val batch = options.batch.getOrElse {
      RefreshLogger.defaultFallbackMode
    }

    val res = (repositoryV, metadataV, singlePackageV, directoryV, checksumV, signatureV, cacheV, verbosityV).mapN {
      (repository, metadata, singlePackage, directory, checksum, signature, cache, verbosity) =>
        PublishParams(
          repository,
          metadata,
          singlePackage,
          directory,
          checksum,
          signature,
          cache,
          verbosity,
          dummy,
          batch,
          sbtOutputFrame
        )
    }

    // TODO Actually take conf file into account beforehand
    // So that e.g. its repository is taken into account and we do not default to sonatype here
    res.withEither { e =>
      for {
        p <- e
        // TODO Warn about ignored fields in conf file?
        confOpt <- options.conf match {
          case None =>
            val loadDefaultIfExists = !p.singlePackage.`package` &&
              p.directory.directories.isEmpty &&
              p.directory.sbtDirectories.forall(_ == Paths.get("."))
            if (loadDefaultIfExists) {
              val default = Paths.get("publish.json")
              val projectDefault = Paths.get("project/publish.json")
              if (Files.isRegularFile(default))
                Conf.load(default)
                  .left.map(NonEmptyList.of(_))
                  .right
                  .map(Some(_))
              else if (Files.isRegularFile(projectDefault))
                Conf.load(projectDefault)
                  .left.map(NonEmptyList.of(_))
                  .right
                  .map(Some(_))
              else
                Right(None)
            } else
              Right(None)
          case Some(c) =>
            val p = Paths.get(c)
            if (Files.exists(p)) {
              if (Files.isRegularFile(p))
                Conf.load(p)
                  .left.map(NonEmptyList.of(_))
                  .right
                  .map(Some(_))
              else
                Left(NonEmptyList.of(s"Conf file $c is not a file"))
            } else
              Left(NonEmptyList.of(s"Conf file $c not found"))
        }
      } yield confOpt.fold(p)(p.withConf)
    }
  }
}
