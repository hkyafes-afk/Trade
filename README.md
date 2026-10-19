# Trade Bot (Binance Spot, sadece testnet)

## Kurulum
```
pip install -r requirements.txt
cp .env.example .env   # testnet anahtarlarını ekle
```

## Testler (ağ gerektirmez)
```
pytest -q
```

## Çalıştırma
```
python -m bot.main
```
`DRY_RUN=true` iken bot emir göndermez, sadece simüle eder ve defterine yazar.
Testnet'te gerçek emir için `.env` içinde `DRY_RUN=false` yap.

## Durdurma (kill switch)
`data/KILL` dosyasını oluşturmak yeni alımları anında engeller. Dosyayı silince bot tekrar alım yapabilir.

## Notlar
- Bu sürüm gerçek ortamı bilerek desteklemez.
- SMA stratejisi örnek amaçlıdır. Kâr garantisi yoktur, testnet sonuçları gerçek performansı göstermez.
- Testnet verileri periyodik olarak sıfırlanır.
