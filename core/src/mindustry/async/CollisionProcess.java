package mindustry.async;

import arc.box2d.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.pooling.*;
import arc.util.pooling.Pool.*;
import mindustry.entities.*;
import mindustry.gen.*;

import static mindustry.Vars.*;

/**
 * Processes collisions.
 *
 * Each entity is assigned a final filter layer, then given a body and inserted into a physics world.
 *
 * Async:
 * The body's position is set to its entity position, and the body velocity is set to the entity delta.
 * Collisions are resolved and stored in a list, then processed synchronously.
 *
 */
public class CollisionProcess implements AsyncProcess{
    private Pool<CollisionRef> pool = Pools.get(CollisionRef.class, CollisionRef::new);

    //private Physics physics;
    private QuadTree<Collisionc> tree;
    private Array<Collisionc> insertEntities = new Array<>();
    private Array<Collisionc> checkEntities = new Array<>();

    private Array<Collisionc> arrOut = new Array<>();

    private Vec2 l1 = new Vec2();
    private Rect r1 = new Rect();
    private Rect r2 = new Rect();
    //private Array<CollisionRef> refs = new Array<>(false);
    //private BodyDef def;
    //private FixtureDef fdef;

    private EntityGroup<? extends Collisionc> inserted = Groups.unit;
    private EntityGroup<? extends Collisionc> checked = Groups.bullet;
    private Array<Collisionc> collisions = new Array<>(Collisionc.class);

    @Override
    public void begin(){
        if(tree == null) return;

        collisions.clear();

        insertEntities.size = 0;
        checkEntities.size = 0;

        inserted.copy(insertEntities);
        checked.copy(checkEntities);
    }

    @Override
    public void process(){
        if(tree == null) return;

        collisions.clear();

        Time.mark();

        tree.clear();
        //insert targets
        for(Collisionc ins : insertEntities){
            tree.insert(ins);
        }

        for(Collisionc solid : checked){
            solid.hitbox(r1);
            r1.x += (solid.lastX() - solid.getX());
            r1.y += (solid.lastY() - solid.getY());

            solid.hitbox(r2);
            r2.merge(r1);

            arrOut.clear();
            tree.getIntersect(arrOut, r2);

            for(Collisionc sc : arrOut){
                sc.hitbox(r1);
                if(r2.overlaps(r1)){
                    checkCollide(solid, sc);
                    //break out of loop when this object hits something
                    if(!solid.isAdded()) return;
                }
            }
        }

        Log.info(Time.elapsed());
    }

    @Override
    public void end(){
        if(tree == null) return;

        //processes anything that happened
        for(int i = 0; i < collisions.size; i += 2){
            Collisionc a = collisions.items[i];
            Collisionc b = collisions.items[i + 1];

            //TODO incorrect
            float cx = (a.x() + b.x())/2f, cy = (a.y() + b.y())/2f;

            a.collision(b, cx, cy);
            b.collision(a, cx, cy);
        }
    }

    @Override
    public void reset(){
        tree = null;
        insertEntities.clear();
        checkEntities.clear();
    }

    @Override
    public void init(){
        reset();

        tree = new QuadTree<>(new Rect(-finalWorldBounds, -finalWorldBounds, world.width() * tilesize + finalWorldBounds * 2, world.height() * tilesize + finalWorldBounds * 2));
    }

    private void checkCollide(Collisionc a, Collisionc b){

        a.hitbox(this.r1);
        b.hitbox(this.r2);

        r1.x += (a.lastX() - a.getX());
        r1.y += (a.lastY() - a.getY());
        r2.x += (b.lastX() - b.getX());
        r2.y += (b.lastY() - b.getY());

        float vax = a.getX() - a.lastX();
        float vay = a.getY() - a.lastY();
        float vbx = b.getX() - b.lastX();
        float vby = b.getY() - b.lastY();

        if(a != b && a.collides(b)){
            l1.set(a.getX(), a.getY());
            boolean collide = r1.overlaps(r2) || collide(r1.x, r1.y, r1.width, r1.height, vax, vay,
            r2.x, r2.y, r2.width, r2.height, vbx, vby, l1);
            if(collide){
                collisions.add(a);
                collisions.add(b);
                //a.collision(b, l1.x, l1.y);
                //b.collision(a, l1.x, l1.y);
            }
        }
    }

    private boolean collide(float x1, float y1, float w1, float h1, float vx1, float vy1,
                            float x2, float y2, float w2, float h2, float vx2, float vy2, Vec2 out){
        float px = vx1, py = vy1;

        vx1 -= vx2;
        vy1 -= vy2;

        float xInvEntry, yInvEntry;
        float xInvExit, yInvExit;

        if(vx1 > 0.0f){
            xInvEntry = x2 - (x1 + w1);
            xInvExit = (x2 + w2) - x1;
        }else{
            xInvEntry = (x2 + w2) - x1;
            xInvExit = x2 - (x1 + w1);
        }

        if(vy1 > 0.0f){
            yInvEntry = y2 - (y1 + h1);
            yInvExit = (y2 + h2) - y1;
        }else{
            yInvEntry = (y2 + h2) - y1;
            yInvExit = y2 - (y1 + h1);
        }

        float xEntry, yEntry;
        float xExit, yExit;

        xEntry = xInvEntry / vx1;
        xExit = xInvExit / vx1;

        yEntry = yInvEntry / vy1;
        yExit = yInvExit / vy1;

        float entryTime = Math.max(xEntry, yEntry);
        float exitTime = Math.min(xExit, yExit);

        if(entryTime > exitTime || xExit < 0.0f || yExit < 0.0f || xEntry > 1.0f || yEntry > 1.0f){
            return false;
        }else{
            float dx = x1 + w1 / 2f + px * entryTime;
            float dy = y1 + h1 / 2f + py * entryTime;

            out.set(dx, dy);

            return true;
        }
    }

    public static class CollisionRef implements Poolable{
        Collisionc entity;
        Body body;
        Vec2 velocity = new Vec2(), position = new Vec2();

        public CollisionRef set(Collisionc entity, Body body){
            this.entity = entity;
            this.body = body;

            position.set(entity);
            return this;
        }

        @Override
        public void reset(){
            entity = null;
            body = null;

            velocity.setZero();
            position.setZero();
        }
    }
}