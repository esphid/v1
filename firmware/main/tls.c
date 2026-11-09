#include "esphid.h"
#include "psa/crypto.h"
#include "mbedtls/pk.h"
#include "mbedtls/x509_crt.h"
#include "esp_random.h"
static char certificate[2048], private_key[512], fingerprint[96];
typedef struct {
    char certificate[2048], private_key[512];
} credentials;
const char *tls_cert(void) {
    return certificate;
}
const char *tls_key(void) {
    return private_key;
}
const char *tls_fingerprint(void) {
    return fingerprint;
}
static int fingerprint_update(void) {
    mbedtls_x509_crt c;
    mbedtls_x509_crt_init(&c);
    int r = mbedtls_x509_crt_parse(&c, (uint8_t *)certificate, strlen(certificate) + 1);
    uint8_t digest[32];
    size_t n = 0;
    if (!r)
        r = psa_hash_compute(PSA_ALG_SHA_256, c.raw.p, c.raw.len, digest, sizeof(digest), &n);
    if (!r) {
        for (size_t i = 0; i < 32; i++)
            snprintf(fingerprint + i * 3, 4, i == 31 ? "%02X" : "%02X:", digest[i]);
    }
    mbedtls_x509_crt_free(&c);
    return r;
}
esp_err_t tls_prepare(bool regenerate) {
    xSemaphoreTakeRecursive(config_mutex, portMAX_DELAY);
    credentials *next = NULL;
    int r = 0;
    if (!regenerate && *certificate)
        goto done;
    r = psa_crypto_init();
    if (r)
        goto done;
    next = calloc(1, sizeof(*next));
    if (!next) {
        r = ESP_ERR_NO_MEM;
        goto done;
    }
    if (!regenerate) {
        size_t size = sizeof(*next);
        if (nvs_get_blob(config_nvs, "tls", next, &size) == ESP_OK && size == sizeof(*next)) {
            memcpy(certificate, next->certificate, sizeof(certificate));
            memcpy(private_key, next->private_key, sizeof(private_key));
            certificate[sizeof(certificate) - 1] = 0;
            private_key[sizeof(private_key) - 1] = 0;
            r = fingerprint_update();
            if (!r)
                goto done;
            certificate[0] = private_key[0] = fingerprint[0] = 0;
        }
    }
    psa_key_attributes_t attrs = PSA_KEY_ATTRIBUTES_INIT;
    psa_key_id_t id = 0;
    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    mbedtls_x509write_cert crt;
    mbedtls_x509write_crt_init(&crt);
    r = psa_crypto_init();
    if (r)
        goto cleanup;
    psa_set_key_type(&attrs, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attrs, 256);
    psa_set_key_usage_flags(&attrs, PSA_KEY_USAGE_SIGN_HASH | PSA_KEY_USAGE_EXPORT);
    psa_set_key_algorithm(&attrs, PSA_ALG_ECDSA(PSA_ALG_SHA_256));
    if ((r = psa_generate_key(&attrs, &id)))
        goto cleanup;
    if ((r = mbedtls_pk_wrap_psa(&pk, id)))
        goto cleanup;
    if ((r = mbedtls_pk_write_key_pem(&pk, (uint8_t *)next->private_key,
                                      sizeof(next->private_key))))
        goto cleanup;
    mbedtls_x509write_crt_set_version(&crt, MBEDTLS_X509_CRT_VERSION_3);
    mbedtls_x509write_crt_set_md_alg(&crt, MBEDTLS_MD_SHA256);
    mbedtls_x509write_crt_set_subject_key(&crt, &pk);
    mbedtls_x509write_crt_set_issuer_key(&crt, &pk);
    char dn[80];
    snprintf(dn, sizeof(dn), "CN=ESPHID-%s,O=ESPHID", device_id);
    uint8_t serial[16];
    esp_fill_random(serial, sizeof(serial));
    serial[0] &= 0x7f;
    if ((r = mbedtls_x509write_crt_set_serial_raw(&crt, serial, sizeof(serial))))
        goto cleanup;
    if ((r = mbedtls_x509write_crt_set_subject_name(&crt, dn)))
        goto cleanup;
    if ((r = mbedtls_x509write_crt_set_issuer_name(&crt, dn)))
        goto cleanup;
    if ((r = mbedtls_x509write_crt_set_validity(&crt, "20200101000000", "20991231235959")))
        goto cleanup;
    if ((r = mbedtls_x509write_crt_set_basic_constraints(&crt, 0, -1)))
        goto cleanup;
    if ((r = mbedtls_x509write_crt_set_key_usage(&crt, MBEDTLS_X509_KU_DIGITAL_SIGNATURE)))
        goto cleanup;
    if ((r = mbedtls_x509write_crt_pem(&crt, (uint8_t *)next->certificate,
                                       sizeof(next->certificate))))
        goto cleanup;
    r = nvs_set_blob(config_nvs, "tls", next, sizeof(*next));
    if (!r)
        r = nvs_commit(config_nvs);
    if (!r) {
        memcpy(certificate, next->certificate, sizeof(certificate));
        memcpy(private_key, next->private_key, sizeof(private_key));
        r = fingerprint_update();
    }
cleanup:
    mbedtls_x509write_crt_free(&crt);
    mbedtls_pk_free(&pk);
    if (id)
        psa_destroy_key(id);
    psa_reset_key_attributes(&attrs);
done:
    if (next) {
        memset(next, 0, sizeof(*next));
        free(next);
    }
    xSemaphoreGiveRecursive(config_mutex);
    return r ? ESP_FAIL : ESP_OK;
}
