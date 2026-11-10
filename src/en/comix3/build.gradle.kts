import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Comix3"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl {
            mirrors(
                "http://127.0.0.1:8766",
            )
        }
    }

    deeplink {
        host("comix.to")
        host("www.comix.to")
        path("/title/..*")
    }
}
