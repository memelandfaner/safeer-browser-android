package com.safeer.mobile.browser.cast
import java.math.BigInteger
fun main() {
    val w = BigInteger("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f", 16)
    val x = BigInteger("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729", 16)
    val y = BigInteger("dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be", 16)
    val a = Spake2.zaTest(w, "server", "client", true, x)
    val b = Spake2.zaTest(w, "client", "server", false, y)
    val pa = a.sporocilo(); val pb = b.sporocilo()
    check(Spake2.hex(pa) == "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c") { "pA" }
    check(Spake2.hex(pb) == "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7") { "pB" }
    val ca = a.zakljuci(pb); val cb = b.zakljuci(pa)
    check(Spake2.hex(a.ke!!) == "0e0672dc86f8e45565d338b0540abe69") { "Ke" }
    check(Spake2.hex(ca) == "58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0") { "cA" }
    check(Spake2.hex(cb) == "d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b") { "cB" }
    check(a.preveri(cb) && b.preveri(ca)) { "preveri" }
    // napacna koda
    val s = Spake2.streznik("482913", "hub", "naprava", sol = "pair1".toByteArray())
    val o = Spake2.odjemalec("482914", "naprava", "hub", sol = "pair1".toByteArray())
    val cs = s.zakljuci(o.sporocilo()); val co = o.zakljuci(s.sporocilo())
    check(!s.preveri(co) && !o.preveri(cs)) { "napacna koda" }
    val s2 = Spake2.streznik("482913", "hub", "naprava", sol = "pair1".toByteArray())
    val o2 = Spake2.odjemalec("482913", "naprava", "hub", sol = "pair1".toByteArray())
    val cs2 = s2.zakljuci(o2.sporocilo()); val co2 = o2.zakljuci(s2.sporocilo())
    check(s2.preveri(co2) && o2.preveri(cs2) && s2.ke!!.contentEquals(o2.ke!!)) { "prava koda" }
    // neveljavna tocka
    try { s2.zakljuci(ByteArray(65) { 4 }); check(false) } catch (e: IllegalArgumentException) {}
    println("SPAKE2 OK: RFC 9382 vektor, prava/napacna koda, neveljavna tocka")
}
