# 🚀 BetbetMiro Extension

<div align="center">

<img src="https://img.shields.io/github/stars/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=yellow" alt="Stars" />
<img src="https://img.shields.io/github/forks/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=blue" alt="Forks" />
<img src="https://img.shields.io/github/license/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=green" alt="License" />
<img src="https://img.shields.io/github/last-commit/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=red" alt="Last Commit" />

<br>

<a href="https://www.githubstatus.com/">
  <img src="https://img.shields.io/badge/dynamic/json?label=GitHub%20Status&query=%24.status.description&url=https%3A%2F%2Fwww.githubstatus.com%2Fapi%2Fv2%2Fstatus.json&logo=github&style=for-the-badge" alt="GitHub Status" />
</a>

</div>

---

## 🎬 CloudStream Extension Repository

**⚡ Fast • Stable • Anime • Donghua • Movies • Drama**

Repository extension CloudStream custom yang berisi berbagai provider anime, donghua, drama, movie, dan source streaming lainnya. Dibuat untuk pengalaman nonton yang lebih praktis, ringan, dan terus diperbarui mengikuti perubahan source.

> Fokus utama repo ini adalah provider yang mudah dipasang, cepat diuji, dan tetap dirawat saat domain, parser, atau extractor berubah.

---

## ✨ Fitur Utama

- 🎥 Streaming langsung melalui aplikasi CloudStream
- ⚡ Ringan, cepat, dan mudah digunakan
- 🔄 Mendukung auto-update extension melalui `repo.json`
- 🧩 Kompatibel dengan CloudStream versi terbaru
- 🌐 Berisi banyak source anime, donghua, drama, movie, dan multi-source
- 📱 Dioptimalkan untuk perangkat Android modern
- 🛠️ Open source dan aktif diperbarui
- 🍿 Cocok untuk pengguna yang ingin repo praktis tanpa ribet

---

## 📥 Install Repository

Pilih salah satu metode instalasi di bawah ini.

### ✅ Metode 1: One-Click Install

Klik tombol/link berikut dari perangkat Android yang sudah terpasang CloudStream:

👉 **[Install BetbetMiro Repository](cloudstreamrepo://raw.githubusercontent.com/sad25kag/BetbetMiro-Extension/master/repo.json)**

### 🧩 Metode 2: Install Manual

Jika metode one-click tidak berjalan, gunakan cara manual:

1. Buka aplikasi **CloudStream**
2. Masuk ke **Settings**
3. Pilih **Extensions**
4. Tekan **Add Repository**
5. Paste link berikut:

```txt
https://raw.githubusercontent.com/sad25kag/BetbetMiro-Extension/master/repo.json
```

6. Tekan **Add / Install**
7. Selesai, tinggal pilih extension yang ingin digunakan 🎉

---

## 🧩 Isi Repository

Repository ini berisi berbagai jenis provider dan komponen pendukung:

- **Anime Providers**
- **Donghua Providers**
- **Movie Providers**
- **Drama Providers**
- **Multi-source Providers**
- **Extractor pendukung**
- Source lain yang akan terus ditambahkan sesuai kebutuhan

> Daftar provider dapat berubah sewaktu-waktu mengikuti kondisi website source, hasil build terbaru, dan laporan issue dari pengguna.

---

## 🛠️ Build From Source

Untuk kamu yang ingin melakukan build mandiri, testing, atau kontribusi:

### 1. Clone Repository

```bash
git clone https://github.com/sad25kag/BetbetMiro-Extension.git
cd BetbetMiro-Extension
```

### 2. Build Extension

```bash
./gradlew make
```

### 3. Output Build

Hasil build akan tersedia di folder:

```txt
/builds
```

### Catatan untuk Provider Fix

Jika melakukan perbaikan provider, pastikan:

- `build.gradle.kts` provider sudah **bump version** agar ikut ter-build.
- Perubahan hanya menyentuh provider/module yang relevan.
- Provider aktif memakai status yang sesuai.
- `search`, `getMainPage`, `load`, dan `loadLinks` tetap aman dari URL kosong, URL relatif, dan parser crash.
- Jangan membaca file/response besar dengan `.text` jika berisiko terkena OOM guard.

Gradle boleh galak, tapi jangan dikasih alasan buat ngamuk 😭

---

## 📸 Preview Alur Install

```txt
CloudStream
 └── Settings
     └── Extensions
         └── Add Repository
             └── BetbetMiro Repo
                 └── Install Extension
                     └── Enjoy 🍿
```

---

## 🔄 Update dan Laporan Error

Repository ini akan terus diperbarui jika ada:

- Source yang berubah domain
- Provider yang error
- Parser yang perlu diperbaiki
- Kategori baru
- Extractor baru
- Perbaikan build Gradle

Kalau ada provider yang tidak berjalan, silakan buka **Issue** dan gunakan template yang sesuai:

- **Provider bermasalah** untuk error search, homepage, load detail, atau video tidak bisa diputar.
- **Build gagal** untuk error GitHub Actions atau compile Gradle.
- **Request provider baru** untuk mengusulkan source/provider baru.

Semakin lengkap contoh judul, URL, log, atau screenshot yang diberikan, semakin cepat provider bisa diperiksa.

---

## 🤝 Kontribusi dan Pull Request

Pull Request sangat diterima, terutama untuk:

- Memperbaiki provider yang rusak
- Mengaktifkan kembali provider yang mati karena domain berubah
- Menambah kategori/genre dari website sumber
- Memperkuat extractor dan fallback playback
- Membersihkan kode tanpa merusak provider yang sudah jalan

Checklist penting sebelum PR:

- Bump `version` di `build.gradle.kts` provider yang diubah.
- Pastikan build/compile sudah dicek atau jelaskan log errornya.
- Jangan mengubah provider lain jika tidak terkait.
- Sertakan ringkasan perubahan yang jelas.

---

## ⚠️ Disclaimer

Repository ini dibuat untuk tujuan pembelajaran, pengembangan, dan penggunaan extension CloudStream.

Semua konten, metadata, gambar, video, maupun source streaming berasal dari pihak ketiga atau sumber publik di internet. Repository ini tidak menyimpan, meng-host, atau mengunggah konten video apa pun.

Gunakan dengan bijak dan patuhi aturan yang berlaku di wilayah masing-masing.

Jika Anda meyakini bahwa ada konten yang melanggar hukum hak cipta, silakan hubungi **pihak penyedia host file yang bersangkutan**, **bukan** pengembang dari repositori ini ataupun aplikasi CloudStream.

---

## 🔞 Peringatan Konten Dewasa (NSFW)

Harap diperhatikan bahwa beberapa ekstensi di dalam repositori ini dapat mengakses konten bernuansa dewasa (NSFW).

- Penggunaan ekstensi ini ditujukan secara tegas **hanya untuk pengguna berusia 18 tahun ke atas** atau sesuai batas usia legal di yurisdiksi/negara Anda.
- Jika Anda belum memenuhi syarat usia atau berada di lingkungan yang tidak mengizinkan konten semacam ini, harap segera tinggalkan halaman ini.
- Pengembang repositori ini tidak bertanggung jawab atas akses yang dilakukan oleh anak di bawah umur.

Dengan mengunduh, menginstal, atau menggunakan ekstensi dari repositori ini, Anda secara sadar mengonfirmasi bahwa Anda telah memenuhi kriteria usia legal dan membebaskan pengembang dari segala tuntutan.

---

## ❤️ Credits

Terima kasih untuk:

- [CloudStream](https://github.com/recloudstream/cloudstream)
- Semua developer provider dan extractor
- Komunitas open source
- Keluarga, teman, dan pengguna anonim yang ikut membantu testing serta melaporkan error
- Gradle, walau sering bikin kepala ingin restart hidup 😭

---

<div align="center">

## 🍿 Happy Streaming

**Made with ☕, semangat, dan error merah Gradle yang tidak ada habisnya.**

</div>
