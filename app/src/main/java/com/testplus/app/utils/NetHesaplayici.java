package com.testplus.app.utils;

import java.util.List;

public class NetHesaplayici {

    public static double hesaplaNet(int dogru, int yanlis, String cezaTuru) {
        double ceza = 0;
        switch (cezaTuru) {
            case Constants.CEZA_BIR_BIR:
                ceza = yanlis;
                break;
            case Constants.CEZA_IKI_BIR:
                ceza = yanlis / 2.0;
                break;
            case Constants.CEZA_UC_BIR:
                ceza = yanlis / 3.0;
                break;
            case Constants.CEZA_DORT_BIR:
                ceza = yanlis / 4.0;
                break;
            default:
                ceza = 0;
                break;
        }
        return dogru - ceza;
    }

    public static int[] karsilastir(List<String> ogrenciCevaplari, List<String> anahtarCevaplari) {
        int dogru = 0, yanlis = 0, bos = 0;
        int toplamSoru = anahtarCevaplari.size();
        for (int i = 0; i < toplamSoru; i++) {
            if (i >= ogrenciCevaplari.size()) {
                bos++;
                continue;
            }
            String ogrenci = ogrenciCevaplari.get(i);
            String anahtar = anahtarCevaplari.get(i);
            if (ogrenci == null || ogrenci.isEmpty()) {
                bos++;
            } else if (ogrenci.equals(anahtar)) {
                dogru++;
            } else {
                yanlis++;
            }
        }
        return new int[]{dogru, yanlis, bos};
    }
}
