"""How the service decides whether to trust Server 1's certificate.

Worth its own tests because the failure modes are asymmetric: getting it wrong in one direction
means the assistant simply cannot reach OpenMRS and somebody notices within a minute, and getting
it wrong in the other means it happily talks to anything presenting any certificate and nobody
notices at all.
"""

from __future__ import annotations

import datetime
import ssl

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from app.config import settings
from app.openmrs_client import _ssl_contexts, tls_verification


@pytest.fixture(autouse=True)
def restore_settings():
    original = (settings.openmrs_verify_tls, settings.openmrs_ca_bundle)
    yield
    settings.openmrs_verify_tls, settings.openmrs_ca_bundle = original
    _ssl_contexts.clear()


@pytest.fixture
def ca_file(tmp_path):
    """A real self-signed CA certificate, of the shape make-certs.sh produces."""
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Test Internal CA")])
    now = datetime.datetime.now(datetime.timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(private_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .sign(private_key, hashes.SHA256())
    )
    path = tmp_path / "hospital-ca.crt"
    path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    return path


def test_verification_is_on_by_default():
    settings.openmrs_verify_tls = True
    settings.openmrs_ca_bundle = ""
    assert tls_verification() is True


def test_a_configured_ca_bundle_still_verifies(ca_file):
    """The internal CA must be *added* to verification, not used to switch it off."""
    settings.openmrs_verify_tls = True
    settings.openmrs_ca_bundle = str(ca_file)

    context = tls_verification()

    assert isinstance(context, ssl.SSLContext)
    assert context.verify_mode == ssl.CERT_REQUIRED
    assert context.check_hostname is True


def test_the_context_is_built_once_per_bundle(ca_file):
    # A client is created per outbound request, so rebuilding the context each time would be a
    # per-call cost for something that never changes.
    settings.openmrs_verify_tls = True
    settings.openmrs_ca_bundle = str(ca_file)

    assert tls_verification() is tls_verification()


def test_verification_can_be_turned_off_only_explicitly(ca_file):
    settings.openmrs_verify_tls = False
    settings.openmrs_ca_bundle = str(ca_file)
    assert tls_verification() is False


class TestStartupValidation:
    """A misconfigured CA path must stop the service starting, not fail every turn."""

    def test_a_good_bundle_passes(self, ca_file):
        settings.openmrs_ca_bundle = str(ca_file)
        settings.validate()

    def test_a_missing_file_is_caught_at_startup(self, tmp_path):
        settings.openmrs_ca_bundle = str(tmp_path / "not-mounted.crt")
        with pytest.raises(RuntimeError, match="not a file"):
            settings.validate()

    def test_a_file_that_is_not_a_certificate_is_caught_at_startup(self, tmp_path):
        # The realistic version of this is an empty file, left behind by a bind mount pointing at
        # a path that did not exist on the host.
        empty = tmp_path / "hospital-ca.crt"
        empty.write_text("")
        settings.openmrs_ca_bundle = str(empty)
        with pytest.raises(RuntimeError, match="not a readable PEM"):
            settings.validate()
