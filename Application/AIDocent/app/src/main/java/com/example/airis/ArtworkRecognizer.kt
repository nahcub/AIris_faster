// 그림 인식(artwork recognition) 이음새.
// InferenceEngine이 '엔진을 갈아끼우는' 추상화였듯, 이건 '작품을 알아내는 방법을 갈아끼우는' 추상화다.
// 지금은 진짜 인식 없이 고정 작품 하나를 반환하는 stub(FixedArtworkRecognizer)뿐.
// 나중에 카메라/이미지 기반 인식을 CameraArtworkRecognizer 같은 구현체로 추가하면,
// 호출부(InferenceScreen)는 recognizer를 바꾸는 한 줄 외엔 건드릴 필요가 없다.
package com.example.airis

interface ArtworkRecognizer {
    // 지금은 입력이 없다(고정값). 진짜 인식 구현체는 여기 이미지/카메라 프레임을 인자로 받게 될 것.
    fun recognize(): Artwork
}

// 인식하지 않고 고정된 작품 하나를 그대로 돌려주는 stub.
// 값은 art_metadata.json의 첫 항목(Darmstadt Madonna)과 동일 — 벤치가 현실적인 길이의
// 시스템 프롬프트를 프리필하도록 실제 데이터를 그대로 하드코딩했다.
class FixedArtworkRecognizer : ArtworkRecognizer {
    override fun recognize(): Artwork = Artwork(
        title = "Darmstadt Madonna",
        author = "HOLBEIN, Hans the Younger",
        type = "religious",
        technique = "Oil on limewood, 147 x 102 cm",
        school = "German",
        date = "1526 and after 1528",
        description = "The Meyer or Darmstadt Madonna is the last, most famous and most effective of " +
            "Holbein's great religious works, above all in its depiction of individual human identities " +
            "combined with spectacular spatial control and illusionism - as exemplified by the ruckled carpet." +
            "Standing in a scalloped niche with projecting consoles, Mary, with the Christ Child in her arms, " +
            "is surrounded by the Meyer family. The hooped crown, an allusion to the German imperial crown, " +
            "identifies her as the Queen of Heaven. Typologically, the painting is a Schutzmantelbild " +
            "(a `Virgin of Pity' painting), in which the donor, Jakob Meyer, invokes and gains divine " +
            "protection for himself and his family. Unusually, the donor is shown as the same size as the Virgin." +
            "Chastened by worldly failure and disgrace, Meyer no longer staunchly outstares the world but has " +
            "his eyes fixed on other realms in meditative intensity. This introspection is echoed by his wives " +
            "the enigmatic enwrapped profile of his first, Magdalena Baer (who had died in 1511) and Dorothea " +
            "Kannengiesser. Before them kneels Anna, the only surviving child, whose portrait drawing in chalk " +
            "shows her with free-flowing hair. Holbein repainted her hair tied in a band after her engagement." +
            "In front of Jakob, in a Raphaelesque triangular pose deployed with subtlety and skill, his two " +
            "deceased sons are depicted. The baby, with curly blonde hair and pudgy cheeks, has affinities with " +
            "the Leonardo type. Also Leonardesque is the prowess shown in the foreshortening of the Christ-child's " +
            "extended arm, and the naturalness of the baby's pose, which recall The Virgin of the Rocks",
    )
}
