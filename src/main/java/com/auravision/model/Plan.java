package com.auravision.model;

/** Single source of truth for pricing and limits. The checkout page reads these from /api/checkout/plans. */
public enum Plan {
    FREE(0, 5, 0),
    PLUS(399, 30, 3),
    PRO(1950, 100, 10),
    ULTRA(24999, 500, 50);

    public final int monthlyRupees, imagesPerDay, videosPerDay;

    Plan(int monthlyRupees, int imagesPerDay, int videosPerDay) {
        this.monthlyRupees = monthlyRupees; this.imagesPerDay = imagesPerDay; this.videosPerDay = videosPerDay;
    }

    /** Yearly billing is 12 months with 15% off. */
    public int price(boolean yearly) {
        return yearly ? (int) Math.round(monthlyRupees * 12 * 0.85) : monthlyRupees;
    }
}
