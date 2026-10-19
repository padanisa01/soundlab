package com.audiofx.app;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time "premium" in-app purchase. Owning it sets the persisted
 * premium flag, which hides every ad in the app (banner + wait-screen
 * ads). restorePurchase() also clears the flag when the Play purchase
 * list contains no active purchase of this product, so a refunded or
 * cancelled purchase revokes premium. Product setup (one-time product
 * with this ID, app published) happens in the Play Console; this class
 * only drives the client side.
 */
public class PremiumBilling implements PurchasesUpdatedListener {

    /** One-time product ID, created in the Play Console. */
    public static final String PRODUCT_ID = "soundlab_premium";

    private static final String PREFS = "soundlab";
    private static final String KEY_PREMIUM = "premium";

    public interface Listener {
        void onPremiumChanged(boolean premium);
    }

    private final Activity activity;
    private final Listener listener;
    private BillingClient client;
    private ProductDetails productDetails;

    public PremiumBilling(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public static boolean isPremium(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PREMIUM, false);
    }

    private static void setPremium(Context context, boolean premium) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PREMIUM, premium).apply();
    }

    /** Connects and restores an existing purchase, if any. */
    public void start() {
        client = BillingClient.newBuilder(activity)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts().build())
                .build();
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode()
                        == BillingClient.BillingResponseCode.OK) {
                    restorePurchase();
                    loadProductDetails();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
            }
        });
    }

    public void destroy() {
        if (client != null) {
            client.endConnection();
            client = null;
        }
    }

    /** Launches the purchase flow for the premium product. */
    public void buyPremium() {
        if (client == null
                || !client.isReady()) {
            return;
        }
        if (productDetails == null) {
            loadProductDetails();
            return;
        }
        List<BillingFlowParams.ProductDetailsParams> paramsList =
                new ArrayList<>();
        paramsList.add(BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build());
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(paramsList)
                .build();
        client.launchBillingFlow(activity, params);
    }

    @Override
    public void onPurchasesUpdated(BillingResult result,
            List<Purchase> purchases) {
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK
                || purchases == null) {
            return;
        }
        for (Purchase p : purchases) {
            if (p.getProducts().contains(PRODUCT_ID)
                    && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                grantPremium(p);
            }
        }
    }

    private void restorePurchase() {
        client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                (result, purchases) -> {
                    if (result.getResponseCode()
                                != BillingClient.BillingResponseCode.OK
                            || purchases == null) {
                        return;
                    }
                    boolean hasActivePurchase = false;
                    for (Purchase p : purchases) {
                        boolean active = p.getProducts().contains(PRODUCT_ID)
                                && p.getPurchaseState()
                                        == Purchase.PurchaseState.PURCHASED;
                        if (active) {
                            hasActivePurchase = true;
                            grantPremium(p);
                        }
                    }
                    // Revocation half of ticket AM-2: the premium flag is
                    // persisted on-device, so if no active purchase exists
                    // (refund, cancellation) and the flag is still set,
                    // clear it. The MainActivity listener only acts when
                    // premium is true, so a false notification is safe;
                    // the cleared flag takes effect for ads on next launch.
                    if (!hasActivePurchase && isPremium(activity)) {
                        setPremium(activity, false);
                        activity.runOnUiThread(
                                () -> listener.onPremiumChanged(false));
                    }
                });
    }

    private void loadProductDetails() {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build());
        QueryProductDetailsParams params = QueryProductDetailsParams
                .newBuilder()
                .setProductList(products)
                .build();
        client.queryProductDetailsAsync(params, (result, details) -> {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && details != null && !details.isEmpty()) {
                productDetails = details.get(0);
            }
        });
    }

    private void grantPremium(Purchase purchase) {
        // Ticket AM-2, on-device verification: reject purchases whose
        // Google signature does not verify against the Play Console
        // license key. The key comes from local.properties
        // (play.license.key) via BuildConfig; it only exists once the Play
        // Console app is created, so until then verification is skipped
        // with a warning and behavior is unchanged.
        String licenseKey = BuildConfig.PLAY_LICENSE_KEY;
        if (licenseKey == null || licenseKey.isEmpty()) {
            Log.w("AudioMechanic",
                    "Play license key not configured: skipping purchase "
                    + "signature verification (set play.license.key in "
                    + "local.properties after creating the Play listing).");
        } else if (!Security.verifyPurchase(licenseKey,
                purchase.getOriginalJson(), purchase.getSignature())) {
            Log.w("AudioMechanic",
                    "Rejecting purchase: signature verification failed.");
            return;
        }
        if (!purchase.isAcknowledged()) {
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams
                    .newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build();
            client.acknowledgePurchase(params, result -> {
            });
        }
        setPremium(activity, true);
        activity.runOnUiThread(() -> listener.onPremiumChanged(true));
    }
}
